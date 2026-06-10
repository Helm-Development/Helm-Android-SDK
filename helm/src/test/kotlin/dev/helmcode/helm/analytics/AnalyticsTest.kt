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
            installationStore = InstallationStore(backing),
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
}
