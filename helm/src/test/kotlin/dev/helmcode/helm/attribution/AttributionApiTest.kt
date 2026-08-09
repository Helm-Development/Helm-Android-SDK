package dev.helmcode.helm.attribution

import dev.helmcode.helm.AttributionStatus
import dev.helmcode.helm.Configuration
import dev.helmcode.helm.HelmResult
import dev.helmcode.helm.PromoCodeLink
import dev.helmcode.helm.analytics.InMemoryStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * End-to-end behaviour of the attribution engine against a real HTTP server.
 *
 * The line every test here is drawn around: **transport failures queue, terminal
 * failures surface**. Getting that backwards either loses submissions or retries
 * a rejection forever.
 */
class AttributionApiTest {

    private val now = 1_765_200_000_000L
    private val day = 24L * 60 * 60 * 1000

    private lateinit var server: MockWebServer
    private lateinit var backing: InMemoryStore
    private lateinit var api: AttributionApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        Configuration.instance = Configuration("pk_test", server.url("/").toString().trimEnd('/'))
        backing = InMemoryStore()
        api = AttributionApi(backing, { "device-uuid" }, { now })
    }

    @After
    fun tearDown() {
        Configuration.instance = null
        server.shutdown()
    }

    /** Nothing is listening on port 1, so every request fails to connect immediately. */
    private fun goOffline() {
        Configuration.instance = Configuration("pk_test", "http://127.0.0.1:1")
    }

    private fun jsonResponse(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    // ---- promo code: happy path ------------------------------------------

    @Test
    fun submitPromoCodeSendsContractBodyAndReturnsLink() = runBlocking {
        server.enqueue(jsonResponse(200, """{"linked": true, "influencer_code": "anna", "offering_id": "off_1"}"""))

        val result = api.submitPromoCode("user-1", "anna")

        assertEquals(HelmResult.Success(PromoCodeLink("anna", "off_1")), result)

        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals(Attribution.PATH_PROMO_CODE, request.path)
        assertEquals("Bearer pk_test", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("\"user_id\":\"user-1\""))
        assertTrue(body, body.contains("\"code\":\"anna\""))
        assertTrue(body, body.contains("\"platform\":\"android\""))
        assertTrue(body, body.contains("\"device_id\":\"device-uuid\""))
    }

    @Test
    fun successfulPromoCodeSubmitSeedsTheStatusCache() = runBlocking {
        server.enqueue(jsonResponse(200, """{"linked": true, "influencer_code": "anna", "offering_id": "off_1"}"""))

        api.submitPromoCode("user-1", "anna")

        // A confirmed link is a complete status, so a later offline read can serve it.
        val cached = api.statusCache.get("user-1")!!
        assertTrue(cached.linked)
        assertEquals("anna", cached.influencerCode)
        assertEquals("off_1", cached.offeringId)
    }

    @Test
    fun promoCodeLinkFallsBackToSubmittedCodeWhenResponseOmitsIt() = runBlocking {
        server.enqueue(jsonResponse(200, """{"linked": true}"""))

        val result = api.submitPromoCode("user-1", "anna") as HelmResult.Success
        assertEquals("anna", result.value.influencerCode)
        assertNull(result.value.offeringId)
    }

    // ---- terminal vs transport ------------------------------------------

    @Test
    fun terminalRejectionSurfacesTheEnvelopeCodeAndDoesNotQueue() = runBlocking {
        server.enqueue(jsonResponse(400, """{"error": {"code": "invalid_code", "message": "No such code."}}"""))

        val result = api.submitPromoCode("user-1", "nope")

        assertEquals(HelmResult.Failure("invalid_code", 400, "No such code."), result)
        assertEquals("a rejection must never be retried", 0, api.pending.count())
    }

    @Test
    fun codeInactiveAndAlreadyLinkedAreTerminal() = runBlocking {
        server.enqueue(jsonResponse(409, """{"error": {"code": "already_linked", "message": "Already linked."}}"""))
        assertEquals(
            HelmResult.Failure("already_linked", 409, "Already linked."),
            api.submitPromoCode("user-1", "anna"),
        )

        server.enqueue(jsonResponse(400, """{"error": {"code": "code_inactive", "message": "Inactive."}}"""))
        assertEquals(
            HelmResult.Failure("code_inactive", 400, "Inactive."),
            api.submitPromoCode("user-1", "anna"),
        )

        assertEquals(0, api.pending.count())
    }

    @Test
    fun unauthorizedIsTerminalAndNotQueued() = runBlocking {
        server.enqueue(jsonResponse(401, """{"error": {"code": "invalid_token", "message": "Bad key."}}"""))

        val result = api.submitPromoCode("user-1", "anna")

        assertEquals(HelmResult.Failure("invalid_token", 401, "Bad key."), result)
        assertEquals(0, api.pending.count())
    }

    @Test
    fun unrecognisedErrorBodyBecomesHttpStatusCode() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("<html>Forbidden</html>"))

        val result = api.submitPromoCode("user-1", "anna") as HelmResult.Failure

        assertEquals("http_403", result.code)
        assertEquals(403, result.httpStatus)
        assertEquals(0, api.pending.count())
    }

    @Test
    fun serverErrorQueuesForReplay() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        assertEquals(HelmResult.Queued, api.submitPromoCode("user-1", "anna"))

        val queued = api.pending.all().single()
        assertEquals(PendingKind.PROMO_CODE, queued.kind)
        assertEquals("user-1", queued.userId)
        assertEquals("anna", queued.code)
        assertEquals(now, queued.enqueuedAtMs)
    }

    @Test
    fun rateLimitQueuesForReplay() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))

        assertEquals(HelmResult.Queued, api.submitPromoCode("user-1", "anna"))
        assertEquals(1, api.pending.count())
    }

    @Test
    fun networkDisconnectQueuesForReplay() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertEquals(HelmResult.Queued, api.submitPromoCode("user-1", "anna"))
        assertEquals(1, api.pending.count())
    }

    @Test
    fun unparseableSuccessBodyQueuesForReplay() = runBlocking {
        // A 2xx we cannot read: the write may have landed, but the endpoint
        // absorbs a duplicate, so retrying is safer than dropping.
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))

        assertEquals(HelmResult.Queued, api.submitPromoCode("user-1", "anna"))
        assertEquals(1, api.pending.count())
    }

    @Test
    fun notConfiguredFailsWithoutQueueing() = runBlocking {
        Configuration.instance = null

        val result = api.submitPromoCode("user-1", "anna")

        assertEquals("not_configured", (result as HelmResult.Failure).code)
        assertNull(result.httpStatus)
        assertEquals(0, api.pending.count())
        assertEquals(0, server.requestCount)
    }

    // ---- transaction submission -----------------------------------------

    @Test
    fun submitTransactionSendsContractBody() = runBlocking {
        server.enqueue(jsonResponse(200, """{"recorded": true}"""))

        assertEquals(HelmResult.Success(Unit), api.submitOriginalTransactionId("user-1", "tx-9"))

        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals(Attribution.PATH_TRANSACTION, request.path)
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("\"user_id\":\"user-1\""))
        assertTrue(body, body.contains("\"original_transaction_id\":\"tx-9\""))
        assertTrue(body, body.contains("\"platform\":\"android\""))
        assertTrue(body, body.contains("\"device_id\":\"device-uuid\""))
    }

    @Test
    fun submitTransactionQueuesTransactionKindOnServerError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("down"))

        assertEquals(HelmResult.Queued, api.submitOriginalTransactionId("user-1", "tx-9"))

        val queued = api.pending.all().single()
        assertEquals(PendingKind.TRANSACTION, queued.kind)
        assertEquals("tx-9", queued.originalTransactionId)
        assertNull(queued.code)
    }

    // ---- replay ----------------------------------------------------------

    @Test
    fun pendingSubmissionsReplayBeforeTheNextCallAndDrain() = runBlocking {
        api.pending.enqueue(PendingSubmission.promoCode("user-1", "anna", now - day))
        server.enqueue(jsonResponse(200, """{"linked": true, "influencer_code": "anna", "offering_id": "off_1"}"""))
        server.enqueue(jsonResponse(200, """{"linked": true, "influencer_code": "anna", "offering_id": "off_1"}"""))

        val result = api.fetchAttributionStatus("user-1")

        // The queued promo goes out FIRST -- otherwise the status read would
        // report stale "unlinked" for a code the user already entered.
        assertEquals(Attribution.PATH_PROMO_CODE, server.takeRequest(2, TimeUnit.SECONDS)!!.path)
        assertEquals(Attribution.PATH_STATUS, server.takeRequest(2, TimeUnit.SECONDS)!!.path)
        assertEquals(0, api.pending.count())
        assertTrue(result is HelmResult.Success)
    }

    @Test
    fun replayInFifoOrder() = runBlocking {
        api.pending.enqueue(PendingSubmission.promoCode("user-1", "second", now - day))
        api.pending.enqueue(PendingSubmission.promoCode("user-1", "first", now - 3 * day))
        repeat(2) { server.enqueue(jsonResponse(200, """{"linked": true}""")) }

        api.replayPending()

        assertTrue(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8().contains("\"code\":\"first\""))
        assertTrue(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8().contains("\"code\":\"second\""))
        assertEquals(0, api.pending.count())
    }

    @Test
    fun replayedSubmissionRejectedTerminallyIsDroppedSilently() = runBlocking {
        api.pending.enqueue(PendingSubmission.promoCode("user-1", "anna", now - day))
        api.pending.enqueue(PendingSubmission.transaction("user-1", "tx-9", now))
        server.enqueue(jsonResponse(400, """{"error": {"code": "invalid_code", "message": "No such code."}}"""))
        server.enqueue(jsonResponse(200, """{"recorded": true}"""))

        api.replayPending()

        // The rejection resolves the entry rather than blocking the queue: the
        // app discovers the truth via fetchAttributionStatus.
        assertEquals(0, api.pending.count())
        assertEquals(2, server.requestCount)
        assertNull("a rejected replay must not fabricate a link", api.statusCache.get("user-1"))
    }

    @Test
    fun replaySuccessRefreshesTheStatusCache() = runBlocking {
        api.pending.enqueue(PendingSubmission.promoCode("user-1", "anna", now - day))
        server.enqueue(jsonResponse(200, """{"linked": true, "influencer_code": "anna", "offering_id": "off_7"}"""))

        api.replayPending()

        val cached = api.statusCache.get("user-1")!!
        assertTrue(cached.linked)
        assertEquals("off_7", cached.offeringId)
    }

    @Test
    fun transportFailureDuringReplayKeepsEntriesAndAbortsTheLoop() = runBlocking {
        api.pending.enqueue(PendingSubmission.promoCode("user-1", "first", now - 3 * day))
        api.pending.enqueue(PendingSubmission.promoCode("user-1", "second", now - day))
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        api.replayPending()

        // The network is down; hammering the remaining entries would just fail too.
        assertEquals(1, server.requestCount)
        assertEquals(2, api.pending.count())
    }

    @Test
    fun replayDropsExpiredEntriesWithoutSendingThem() = runBlocking {
        api.pending.enqueue(PendingSubmission.promoCode("stale", "old", now - 31 * day))

        api.replayPending()

        assertEquals(0, server.requestCount)
        assertEquals(0, api.pending.count())
    }

    @Test
    fun replayStopsWhenTheSdkIsNotConfigured() = runBlocking {
        api.pending.enqueue(PendingSubmission.promoCode("user-1", "anna", now))
        Configuration.instance = null

        api.replayPending()

        assertEquals("nothing to replay against -- keep the entry", 1, api.pending.count())
    }

    // ---- status fetch + cache -------------------------------------------

    @Test
    fun fetchStatusSendsContractBodyAndReturnsFreshStatus() = runBlocking {
        server.enqueue(jsonResponse(200, """{"linked": true, "influencer_code": "anna", "offering_id": "off_1"}"""))

        val result = api.fetchAttributionStatus("user-1")

        assertEquals(
            HelmResult.Success(AttributionStatus(true, "anna", "off_1", fromCache = false)),
            result,
        )
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals(Attribution.PATH_STATUS, request.path)
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("\"user_id\":\"user-1\""))
        assertTrue(body, body.contains("\"platform\":\"android\""))
        assertTrue(body, body.contains("\"device_id\":\"device-uuid\""))
        assertFalse("a status read carries no code", body.contains("\"code\""))
    }

    @Test
    fun unlinkedStatusIsASuccessNotAFailure() = runBlocking {
        server.enqueue(jsonResponse(200, """{"linked": false}"""))

        assertEquals(
            HelmResult.Success(AttributionStatus(false, null, null, fromCache = false)),
            api.fetchAttributionStatus("user-1"),
        )
    }

    @Test
    fun offlineStatusFetchFallsBackToTheCache() = runBlocking {
        server.enqueue(jsonResponse(200, """{"linked": true, "influencer_code": "anna", "offering_id": "off_1"}"""))
        api.fetchAttributionStatus("user-1")

        goOffline()
        val result = api.fetchAttributionStatus("user-1")

        assertEquals(
            HelmResult.Success(AttributionStatus(true, "anna", "off_1", fromCache = true)),
            result,
        )
    }

    @Test
    fun offlineStatusFetchForAnUncachedUserFails() = runBlocking {
        server.enqueue(jsonResponse(200, """{"linked": true, "influencer_code": "anna", "offering_id": "off_1"}"""))
        api.fetchAttributionStatus("user-1")

        goOffline()
        val result = api.fetchAttributionStatus("user-2")

        // user-1's link must never be served for user-2.
        assertEquals(HelmResult.Failure("network_error"), result)
    }

    @Test
    fun terminalStatusFailureDoesNotFallBackToTheCache() = runBlocking {
        server.enqueue(jsonResponse(200, """{"linked": true, "influencer_code": "anna", "offering_id": "off_1"}"""))
        api.fetchAttributionStatus("user-1")

        server.enqueue(jsonResponse(401, """{"error": {"code": "invalid_token", "message": "Bad key."}}"""))
        val result = api.fetchAttributionStatus("user-1")

        // The server answered: its answer wins over a stale local copy.
        assertEquals(HelmResult.Failure("invalid_token", 401, "Bad key."), result)
        assertEquals("anna", api.statusCache.get("user-1")?.influencerCode)
    }

    @Test
    fun statusFetchNeverQueues() = runBlocking {
        goOffline()

        api.fetchAttributionStatus("user-1")

        assertEquals("a read has nothing to replay", 0, api.pending.count())
    }

    // ---- reset -----------------------------------------------------------

    @Test
    fun resetWipesQueueAndCache() {
        // Seeded directly: routing this through submit + fetch would let the
        // next-call replay trigger drain the queue before reset() ran.
        api.pending.enqueue(PendingSubmission.promoCode("user-1", "anna", now))
        api.pending.enqueue(PendingSubmission.transaction("user-1", "tx-9", now))
        api.statusCache.put("user-1", CachedStatus(true, "anna", "off_1", now))
        api.statusCache.put("user-2", CachedStatus(true, "bruno", null, now))

        api.reset()

        assertEquals(0, api.pending.count())
        assertNull(api.statusCache.get("user-1"))
        assertNull(api.statusCache.get("user-2"))
    }

    @Test
    fun queuedSubmissionIsReplayedByTheVeryNextCall() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertEquals(HelmResult.Queued, api.submitPromoCode("user-1", "anna"))

        // Second attempt: the queued entry replays first, then the new submit.
        server.enqueue(jsonResponse(200, """{"linked": true, "influencer_code": "anna"}"""))
        server.enqueue(jsonResponse(200, """{"linked": true, "influencer_code": "anna"}"""))
        val result = api.submitPromoCode("user-1", "anna")

        assertTrue(result is HelmResult.Success)
        assertEquals(3, server.requestCount)
        assertEquals(0, api.pending.count())
    }

    // ---- pure body builders ---------------------------------------------

    @Test
    fun bodyBuildersProduceExactlyTheContractFields() {
        assertEquals(
            mapOf(
                "user_id" to "u",
                "code" to "c",
                "platform" to "android",
                "device_id" to "d",
            ),
            AttributionApi.promoCodeBody("u", "c", "d"),
        )
        assertEquals(
            mapOf("user_id" to "u", "platform" to "android", "device_id" to "d"),
            AttributionApi.statusBody("u", "d"),
        )
        assertEquals(
            mapOf(
                "user_id" to "u",
                "original_transaction_id" to "tx",
                "platform" to "android",
                "device_id" to "d",
            ),
            AttributionApi.transactionBody("u", "tx", "d"),
        )
    }

    @Test
    fun userIdIsPassedThroughVerbatim() {
        // Opaque RevenueCat app user id: no trimming, no lowercasing, no validation.
        val messy = "  RCAnonymousID:AbC_123  "
        assertEquals(messy, AttributionApi.promoCodeBody(messy, "c", "d")["user_id"])
        assertEquals(messy, AttributionApi.statusBody(messy, "d")["user_id"])
        assertEquals(messy, AttributionApi.transactionBody(messy, "tx", "d")["user_id"])
    }

    @Test
    fun errorEnvelopeParsingPrefersTheContractCode() {
        val parsed = AttributionApi.parseErrorEnvelope(400, """{"error": {"code": "invalid_code", "message": "m"}}""")
        assertEquals(HelmResult.Failure("invalid_code", 400, "m"), parsed)
    }

    @Test
    fun errorEnvelopeParsingFallsBackForNonEnvelopeBodies() {
        assertEquals("http_400", AttributionApi.parseErrorEnvelope(400, "").code)
        assertEquals("http_502", AttributionApi.parseErrorEnvelope(502, "<html/>").code)
        assertEquals("http_400", AttributionApi.parseErrorEnvelope(400, """{"detail": "nope"}""").code)
        assertEquals("http_400", AttributionApi.parseErrorEnvelope(400, """{"error": {"message": "no code"}}""").code)
    }
}
