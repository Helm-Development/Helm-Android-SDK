package dev.helmcode.helm.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AnalyticsClientTest {

    private val device = DeviceFacts(
        platform = "android",
        appVersion = "1.2.3",
        osVersion = "14",
        locale = "en-US",
        timezone = "America/New_York",
    )

    @Test
    fun registrationBodyIncludesRequiredFieldsAndHash() {
        val body = AnalyticsClient.registrationBody(
            installationId = "bbbb1111-2222-3333-4444-bbbb11112222",
            userHash = "a".repeat(32),
            device = device,
        )
        assertEquals("bbbb1111-2222-3333-4444-bbbb11112222", body["installation_id"])
        assertEquals("a".repeat(32), body["user_hash"])
        for (key in listOf("platform", "app_version", "os_version", "locale", "timezone")) {
            assertFalse("$key must be non-empty", (body[key] as String).isEmpty())
        }
        assertFalse("locale must be hyphenated", (body["locale"] as String).contains("_"))
    }

    // ---- debug / sandbox marker (HELM-238) ------------------------------

    @Test
    fun registrationBodyMarksADebugBuild() {
        val body = AnalyticsClient.registrationBody(
            installationId = "iid",
            userHash = "",
            device = device,
            debug = true,
        )
        assertEquals(
            "a debug build must register as debug so Helm leaves it out of active-user counts",
            true, body["debug"],
        )
    }

    @Test
    fun registrationBodyDefaultsToLive() {
        val body = AnalyticsClient.registrationBody(
            installationId = "iid",
            userHash = "",
            device = device,
        )
        assertEquals(
            "an unconfigured build counts as a real user, matching the server default",
            false, body["debug"],
        )
    }

    @Test
    fun registrationBodyIncludesAttributionTokenWhenPresent() {
        val body = AnalyticsClient.registrationBody(
            installationId = "iid",
            userHash = "",
            device = device,
            attributionToken = "attr-abc",
        )
        assertEquals("attr-abc", body["attribution_token"])
    }

    @Test
    fun registrationBodyOmitsAttributionTokenWhenNullOrEmpty() {
        val nullToken = AnalyticsClient.registrationBody("iid", "", device, null)
        val emptyToken = AnalyticsClient.registrationBody("iid", "", device, "")
        assertFalse(nullToken.containsKey("attribution_token"))
        assertFalse(emptyToken.containsKey("attribution_token"))
    }

    // ---- environment label (HELM-242) -----------------------------------

    @Test
    fun registrationBodyCarriesTheConfiguredEnvironment() {
        val body = AnalyticsClient.registrationBody(
            installationId = "iid",
            userHash = "",
            device = device,
            debug = true,
            environment = "staging",
        )
        assertEquals("staging", body["environment"])
        assertEquals(
            "environment is independent of debug -- both are sent",
            true, body["debug"],
        )
    }

    @Test
    fun registrationBodyDefaultsToProductionEnvironment() {
        val body = AnalyticsClient.registrationBody(
            installationId = "iid",
            userHash = "",
            device = device,
        )
        assertEquals(
            "a build that sets no environment reports production",
            "production", body["environment"],
        )
    }

    @Test
    fun eventsBodyShape() {
        val event = AnalyticsEvent("tapped", 1_700_000_000_000L, "s")
        val body = AnalyticsClient.eventsBody("iid", listOf(event))
        assertEquals("iid", body["installation_id"])
        @Suppress("UNCHECKED_CAST")
        val events = body["events"] as List<Map<String, Any?>>
        assertEquals(1, events.size)
        assertEquals("tapped", events.first()["event_name"])
    }

    @Test
    fun eventPayloadCarriesItsOwnDebugAndEnvironment() {
        val event = AnalyticsEvent(
            eventName = "tapped",
            occurredAtMs = 1_700_000_000_000L,
            sessionId = "s",
            debug = true,
            environment = "staging",
        )
        val payload = event.payload()
        assertEquals(true, payload["debug"])
        assertEquals("staging", payload["environment"])
    }

    @Test
    fun eventPayloadDefaultsToLiveProduction() {
        val payload = AnalyticsEvent("tapped", 1_700_000_000_000L, "s").payload()
        assertEquals(false, payload["debug"])
        assertEquals("production", payload["environment"])
    }

    @Test
    fun everyEventInABatchCarriesItsOwnValues() {
        val staging = AnalyticsEvent(
            "first", 1_700_000_000_000L, "s", debug = true, environment = "staging",
        )
        val production = AnalyticsEvent(
            "second", 1_700_000_001_000L, "s", debug = false, environment = "production",
        )
        val body = AnalyticsClient.eventsBody("iid", listOf(staging, production))
        @Suppress("UNCHECKED_CAST")
        val events = body["events"] as List<Map<String, Any?>>
        assertEquals(listOf(true, false), events.map { it["debug"] })
        assertEquals(listOf("staging", "production"), events.map { it["environment"] })
    }
}
