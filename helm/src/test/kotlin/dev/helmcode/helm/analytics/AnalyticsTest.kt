package dev.helmcode.helm.analytics

import dev.helmcode.helm.Configuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.UUID

class AnalyticsTest {

    private val device = DeviceFacts("android", "1.2.3", "14", "en-US", "UTC")

    private fun makeAnalytics(): Analytics {
        val backing = InMemoryStore()
        return Analytics(
            installationStore = InstallationStore(DeviceIdStore(backing)),
            identityStore = IdentityStore(backing),
            sessionManager = SessionManager(),
            queue = EventQueue(),
            deviceFactsOverride = device,
        )
    }

    @Before
    fun setUp() {
        Configuration.instance = Configuration("pk_test", "https://example.invalid")
    }

    @After
    fun tearDown() {
        Configuration.instance = null
    }

    @Test
    fun headersWhenAnonymous() {
        val headers = makeAnalytics().headers()
        assertNotNull(UUID.fromString(headers["X-Helm-Installation-Id"]))
        assertNotNull(UUID.fromString(headers["X-Helm-Session-Id"]))
        assertEquals("android", headers["X-Helm-Platform"])
        assertEquals("1.2.3", headers["X-Helm-App-Version"])
        assertNull(headers["X-Helm-User-Hash"])
    }

    @Test
    fun headersAfterIdentifyAndClear() {
        val analytics = makeAnalytics()
        analytics.identify("b".repeat(32))
        assertEquals("b".repeat(32), analytics.headers()["X-Helm-User-Hash"])
        analytics.clearIdentity()
        assertNull(analytics.headers()["X-Helm-User-Hash"])
    }

    @Test
    fun trackBeforeStartIsSafeNoop() {
        val analytics = makeAnalytics()
        analytics.track("ignored")
        assertEquals(0, analytics.queuedEventCount())
    }

    @Test
    fun trackAfterStartQueues() {
        val analytics = makeAnalytics()
        analytics.startForTest()
        analytics.track("tapped", mapOf("screen" to "home"))
        assertEquals(1, analytics.queuedEventCount())
    }

    @Test
    fun startTwiceIsIdempotent() {
        val analytics = makeAnalytics()
        analytics.startForTest()
        analytics.startForTest()
        analytics.track("once")
        assertEquals(1, analytics.queuedEventCount())
    }

    @Test
    fun headersAreEmptyBeforeStart() {
        val analytics = Analytics(
            installationStore = null,
            identityStore = null,
            sessionManager = SessionManager(),
            queue = EventQueue(),
        )
        assertEquals(emptyMap<String, String>(), analytics.headers())
    }

    @Test
    fun onAttributionMatchedStoresTokenForRegistration() {
        val analytics = makeAnalytics()
        analytics.startForTest()
        analytics.onAttributionMatched("attr-xyz")
        assertEquals("attr-xyz", analytics.attributionTokenForTest())
    }

    @Test
    fun onAttributionMatchedIgnoresEmptyToken() {
        val analytics = makeAnalytics()
        analytics.startForTest()
        analytics.onAttributionMatched("")
        assertNull(analytics.attributionTokenForTest())
    }

    @Test
    fun onAttributionMatchedBeforeStartStoresToken() {
        val analytics = makeAnalytics()
        analytics.onAttributionMatched("attr-early")
        assertEquals("attr-early", analytics.attributionTokenForTest())
    }

    // ---- per-event debug and environment (HELM-242) ----------------------

    private fun analyticsWith(queue: EventQueue): Analytics {
        val backing = InMemoryStore()
        return Analytics(
            installationStore = InstallationStore(DeviceIdStore(backing)),
            identityStore = IdentityStore(backing),
            sessionManager = SessionManager(),
            queue = queue,
            deviceFactsOverride = device,
        )
    }

    @Test
    fun trackedEventCapturesTheConfiguredDebugAndEnvironment() {
        Configuration.instance = Configuration(
            "pk_test", "https://example.invalid", debug = true, environment = "staging",
        )
        val queue = EventQueue()
        val analytics = analyticsWith(queue)
        analytics.startForTest()

        analytics.track("tapped")

        val payload = queue.drain().single().payload()
        assertEquals(true, payload["debug"])
        assertEquals("staging", payload["environment"])
    }

    @Test
    fun trackedEventDefaultsToLiveProduction() {
        // setUp() configured the SDK without debug or environment.
        val queue = EventQueue()
        val analytics = analyticsWith(queue)
        analytics.startForTest()

        analytics.track("tapped")

        val payload = queue.drain().single().payload()
        assertEquals(false, payload["debug"])
        assertEquals("production", payload["environment"])
    }

    @Test
    fun anEventKeepsTheValuesItWasCreatedWithAcrossAReconfiguration() {
        Configuration.instance = Configuration(
            "pk_test", "https://example.invalid", debug = true, environment = "staging",
        )
        val queue = EventQueue()
        val analytics = analyticsWith(queue)
        analytics.startForTest()
        analytics.track("queued_under_staging")

        // The app reconfigures before the batch is flushed. The event already
        // queued must still report the configuration it happened under.
        Configuration.instance = Configuration(
            "pk_test", "https://example.invalid", debug = false, environment = "production",
        )
        analytics.track("queued_under_production")

        val payloads = queue.drain().map { it.payload() }
        assertEquals(
            listOf("queued_under_staging", "queued_under_production"),
            payloads.map { it["event_name"] },
        )
        assertEquals(listOf(true, false), payloads.map { it["debug"] })
        assertEquals(listOf("staging", "production"), payloads.map { it["environment"] })
    }
}
