package dev.helmcode.helm.attribution

import android.util.Log
import dev.helmcode.helm.AttributionStatus
import dev.helmcode.helm.Configuration
import dev.helmcode.helm.HelmResult
import dev.helmcode.helm.PromoCodeLink
import dev.helmcode.helm.analytics.KeyValueStore
import dev.helmcode.helm.attribution.Attribution.Companion.PATH_PROMO_CODE
import dev.helmcode.helm.attribution.Attribution.Companion.PATH_STATUS
import dev.helmcode.helm.attribution.Attribution.Companion.PATH_TRANSACTION
import dev.helmcode.helm.networking.HelmError
import dev.helmcode.helm.networking.HelmHttpClient
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject

/**
 * The attribution engine behind [Attribution]'s promo-code, status, and
 * transaction calls: request bodies, [HelmError] classification, the offline
 * queue, the status cache, and replay.
 *
 * Constructor-injected persistence, device id, and clock keep it fully
 * unit-testable with no Context and no Robolectric -- the same design as
 * `Analytics`' injected stores. Only [HelmHttpClient] reaches the network, and
 * that is redirected at a MockWebServer in tests via `Configuration.instance`.
 */
internal class AttributionApi(
    store: KeyValueStore,
    private val deviceId: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    companion object {
        private const val TAG = "HelmSDK"

        /** Wire value for this SDK; the backend keys promo-code scope on it. */
        internal const val PLATFORM = "android"

        // `debug` is ALWAYS present on all three bodies, as a real JSON boolean.
        // The server reads it as `data.get('debug') is True`, so a missing field
        // or a string "true" both mean live -- sending it explicitly is what
        // makes a sandbox submission legible.

        internal fun promoCodeBody(
            userId: String,
            code: String,
            deviceId: String,
            debug: Boolean,
        ): Map<String, Any?> = mapOf(
            "user_id" to userId,
            "code" to code,
            "platform" to PLATFORM,
            "device_id" to deviceId,
            "debug" to debug,
        )

        internal fun statusBody(userId: String, deviceId: String, debug: Boolean): Map<String, Any?> = mapOf(
            "user_id" to userId,
            "platform" to PLATFORM,
            "device_id" to deviceId,
            "debug" to debug,
        )

        internal fun transactionBody(
            userId: String,
            originalTransactionId: String,
            deviceId: String,
            debug: Boolean,
        ): Map<String, Any?> = mapOf(
            "user_id" to userId,
            "original_transaction_id" to originalTransactionId,
            "platform" to PLATFORM,
            "device_id" to deviceId,
            "debug" to debug,
        )

        /**
         * The configured sandbox marker, or live when the SDK is unconfigured.
         *
         * Read at the moment of each *fresh* submission. Replays deliberately do
         * not consult this -- they use the value the queued entry captured.
         */
        internal fun currentDebug(): Boolean = Configuration.instance?.debug ?: false

        /**
         * Maps a 4xx error body onto a [HelmResult.Failure].
         *
         * The contract envelope is `{"error": {"message", "code"}}`. Anything
         * else -- an HTML error page, a bare string, a proxy response -- becomes
         * `http_<status>` with the raw body as the message, so a terminal
         * failure is never mistaken for a transport one.
         */
        internal fun parseErrorEnvelope(status: Int, rawBody: String): HelmResult.Failure {
            try {
                val error = JSONObject(rawBody).optJSONObject("error")
                val code = error?.optString("code")?.takeIf { it.isNotEmpty() }
                if (code != null) {
                    return HelmResult.Failure(
                        code = code,
                        httpStatus = status,
                        message = error.optString("message").takeIf { it.isNotEmpty() },
                    )
                }
            } catch (_: Exception) {
                // fall through to the raw-body form
            }
            return HelmResult.Failure(
                code = "http_$status",
                httpStatus = status,
                message = rawBody.takeIf { it.isNotEmpty() },
            )
        }
    }

    internal val pending = PendingSubmissionStore(store, clock)
    internal val statusCache = AttributionStatusCache(store)

    /** Single-flight guard: a second replay while one is running is skipped, not queued. */
    private val replayLock = Mutex()

    /**
     * Links [userId] to the influencer promo code [code].
     *
     * A transport failure persists the submission and returns
     * [HelmResult.Queued]; a server rejection returns [HelmResult.Failure] and
     * is never retried.
     */
    suspend fun submitPromoCode(userId: String, code: String): HelmResult<PromoCodeLink> {
        replayPending()
        // Captured once, before the request: the same value stamps the request
        // and -- if it has to be queued -- the queued entry.
        val debug = currentDebug()
        return when (val outcome = post(PATH_PROMO_CODE, promoCodeBody(userId, code, deviceId(), debug))) {
            is Outcome.Ok -> {
                val link = PromoCodeLink(
                    influencerCode = outcome.body["influencer_code"] as? String ?: code,
                    offeringId = outcome.body["offering_id"] as? String,
                )
                cacheLink(userId, link, debug)
                HelmResult.Success(link)
            }
            Outcome.Transport -> {
                pending.enqueue(PendingSubmission.promoCode(userId, code, clock(), debug))
                HelmResult.Queued
            }
            is Outcome.Terminal -> outcome.failure
        }
    }

    /**
     * Reads [userId]'s link state.
     *
     * Never returns [HelmResult.Queued] -- a read has nothing to replay. When
     * the network is unavailable it falls back to the cached status
     * (`fromCache = true`), and only fails when there is no cache to serve.
     *
     * The fallback is environment-aware: a cached entry recorded under the other
     * `debug` setting is treated as a **miss**, not served. A sandbox build must
     * never present a live link as its own (or the reverse); the app sees
     * `network_error` and can refetch when it is online.
     */
    suspend fun fetchAttributionStatus(userId: String): HelmResult<AttributionStatus> {
        replayPending()
        val debug = currentDebug()
        return when (val outcome = post(PATH_STATUS, statusBody(userId, deviceId(), debug))) {
            is Outcome.Ok -> {
                val status = CachedStatus(
                    linked = outcome.body["linked"] as? Boolean ?: false,
                    influencerCode = outcome.body["influencer_code"] as? String,
                    offeringId = outcome.body["offering_id"] as? String,
                    fetchedAtMs = clock(),
                    debug = debug,
                )
                statusCache.put(userId, status)
                HelmResult.Success(status.toPublic(fromCache = false))
            }
            Outcome.Transport -> statusCache.get(userId)
                ?.takeIf { it.debug == debug }
                ?.let { HelmResult.Success(it.toPublic(fromCache = true)) }
                ?: HelmResult.Failure("network_error")
            // The server answered: no cache fallback, the answer is authoritative.
            is Outcome.Terminal -> outcome.failure
        }
    }

    /** Reports the store's original transaction id for [userId]. */
    suspend fun submitOriginalTransactionId(
        userId: String,
        originalTransactionId: String,
    ): HelmResult<Unit> {
        replayPending()
        val debug = currentDebug()
        val body = transactionBody(userId, originalTransactionId, deviceId(), debug)
        return when (val outcome = post(PATH_TRANSACTION, body)) {
            is Outcome.Ok -> HelmResult.Success(Unit)
            Outcome.Transport -> {
                pending.enqueue(
                    PendingSubmission.transaction(userId, originalTransactionId, clock(), debug),
                )
                HelmResult.Queued
            }
            is Outcome.Terminal -> outcome.failure
        }
    }

    /**
     * Drains the offline queue, oldest first.
     *
     * Per entry: success or terminal rejection removes it (a queued code that
     * turns out to be invalid resolves silently -- the app learns the truth from
     * a status fetch); a transport failure keeps it and **aborts the loop**,
     * because the network is down and the remaining entries would fail the same
     * way. Expired entries are dropped by [PendingSubmissionStore.all].
     *
     * Replay is safe to repeat: all three endpoints are idempotent server-side,
     * and the queue dedupes at enqueue time.
     */
    suspend fun replayPending() {
        if (!replayLock.tryLock()) return
        try {
            for (entry in pending.all()) {
                val request = requestFor(entry)
                if (request == null) {
                    // Missing the field its endpoint needs -- unreplayable.
                    Log.w(TAG, "dropping malformed pending ${entry.kind.wire} submission")
                    pending.remove(entry.id)
                    continue
                }
                when (val outcome = post(request.first, request.second)) {
                    is Outcome.Ok -> {
                        if (entry.kind == PendingKind.PROMO_CODE && entry.code != null) {
                            cacheLink(
                                entry.userId,
                                PromoCodeLink(
                                    influencerCode = outcome.body["influencer_code"] as? String ?: entry.code,
                                    offeringId = outcome.body["offering_id"] as? String,
                                ),
                                entry.debug,
                            )
                        }
                        pending.remove(entry.id)
                    }
                    is Outcome.Terminal -> {
                        if (outcome.failure.code == "not_configured") return
                        Log.w(
                            TAG,
                            "pending ${entry.kind.wire} submission rejected " +
                                "(${outcome.failure.code}) -- dropping",
                        )
                        pending.remove(entry.id)
                    }
                    Outcome.Transport -> {
                        // Network is down: keep this entry and stop; a later
                        // trigger retries from here.
                        return
                    }
                }
            }
        } finally {
            replayLock.unlock()
        }
    }

    /** Wipes the queue and the status cache. Match state and device id are untouched. */
    fun reset() {
        pending.clear()
        statusCache.clear()
    }

    // internals -------------------------------------------------------------

    /**
     * The classification that drives every queue/surface decision:
     * transport-class failures are retryable, terminal ones are the server's
     * final answer.
     */
    private sealed class Outcome {
        data class Ok(val body: Map<String, Any?>) : Outcome()
        object Transport : Outcome()
        data class Terminal(val failure: HelmResult.Failure) : Outcome()
    }

    private suspend fun post(path: String, body: Map<String, Any?>): Outcome = try {
        Outcome.Ok(HelmHttpClient.post(path, body))
    } catch (e: HelmError.NotConfigured) {
        Outcome.Terminal(HelmResult.Failure("not_configured", null, e.message))
    } catch (e: HelmError.ServerError) {
        // 429 and 5xx are "try again later"; every other 4xx is the final answer.
        if (e.code == 429 || e.code >= 500) Outcome.Transport else Outcome.Terminal(parseErrorEnvelope(e.code, e.message))
    } catch (e: HelmError.NetworkError) {
        Outcome.Transport
    } catch (e: HelmError.InvalidResponse) {
        // A 2xx we could not parse: the write may well have landed, and all
        // three endpoints absorb a duplicate, so retrying is the safe choice.
        Outcome.Transport
    } catch (e: Exception) {
        Log.w(TAG, "attribution request to $path failed: ${e.message}", e)
        Outcome.Transport
    }

    /**
     * Endpoint + body for a queued entry, or null when the entry cannot be
     * replayed.
     *
     * The body carries **`entry.debug`**, never [currentDebug]. A submission made
     * from a sandbox build and replayed after the app shipped live is still a
     * sandbox submission; re-reading the configuration here would relabel it.
     */
    private fun requestFor(entry: PendingSubmission): Pair<String, Map<String, Any?>>? =
        when (entry.kind) {
            PendingKind.PROMO_CODE -> entry.code?.let {
                PATH_PROMO_CODE to promoCodeBody(entry.userId, it, deviceId(), entry.debug)
            }
            PendingKind.TRANSACTION -> entry.originalTransactionId?.let {
                PATH_TRANSACTION to transactionBody(entry.userId, it, deviceId(), entry.debug)
            }
        }

    /** A confirmed link is a complete status -- record it so a later offline read can serve it. */
    private fun cacheLink(userId: String, link: PromoCodeLink, debug: Boolean) {
        statusCache.put(
            userId,
            CachedStatus(
                linked = true,
                influencerCode = link.influencerCode,
                offeringId = link.offeringId,
                fetchedAtMs = clock(),
                debug = debug,
            ),
        )
    }

    private fun CachedStatus.toPublic(fromCache: Boolean) = AttributionStatus(
        isLinked = linked,
        influencerCode = influencerCode,
        offeringId = offeringId,
        fromCache = fromCache,
    )
}
