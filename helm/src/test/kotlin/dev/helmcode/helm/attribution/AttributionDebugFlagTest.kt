package dev.helmcode.helm.attribution

import dev.helmcode.helm.Configuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HELM-242: the two older attribution requests, `match` and `event`, send the
 * same `debug` boolean the three influencer endpoints already send, so every
 * attribution request the SDK makes carries the sandbox marker.
 *
 * `environment` is deliberately absent from all of them -- it is an
 * analytics-only field.
 */
class AttributionDebugFlagTest {

    @After
    fun tearDown() {
        Configuration.instance = null
    }

    private fun signals() = DeviceSignals.from(
        widthPixels = 1080,
        heightPixels = 2340,
        density = 2.75f,
        timezoneId = "America/New_York",
        localeTag = "en-US",
        osVersion = "14",
    )

    @Test
    fun `match body sends debug as a real boolean`() {
        val debugBody = Attribution.buildMatchBody(signals(), "device-abc", debug = true)
        val liveBody = Attribution.buildMatchBody(signals(), "device-abc", debug = false)

        assertEquals(true, debugBody["debug"])
        assertEquals(false, liveBody["debug"])
    }

    @Test
    fun `match body defaults to live`() {
        assertEquals(false, Attribution.buildMatchBody(signals(), "device-abc")["debug"])
    }

    @Test
    fun `match body never sends environment`() {
        val body = Attribution.buildMatchBody(signals(), "device-abc", debug = true)

        assertFalse("environment is analytics-only", body.containsKey("environment"))
    }

    @Test
    fun `event body sends debug as a real boolean`() {
        val debugBody = Attribution.buildEventBody("purchase", null, "attr-1", debug = true)
        val liveBody = Attribution.buildEventBody("purchase", null, "attr-1", debug = false)

        assertEquals(true, debugBody["debug"])
        assertEquals(false, liveBody["debug"])
    }

    @Test
    fun `event body defaults to live`() {
        assertEquals(false, Attribution.buildEventBody("purchase", null, "attr-1")["debug"])
    }

    @Test
    fun `event body keeps its existing shape alongside debug`() {
        val withMetadata = Attribution.buildEventBody(
            eventType = "purchase",
            metadata = mapOf("sku" to "pro_monthly"),
            attributionId = "attr-1",
            debug = true,
        )
        assertEquals("purchase", withMetadata["event_type"])
        assertEquals("attr-1", withMetadata["attribution_id"])
        assertEquals(mapOf("sku" to "pro_monthly"), withMetadata["metadata"])
        assertFalse("environment is analytics-only", withMetadata.containsKey("environment"))

        // An unmatched install still reports its conversions, and metadata is
        // omitted rather than sent as null when the caller passes none.
        val withoutMetadata = Attribution.buildEventBody("purchase", null, null)
        assertTrue(withoutMetadata.containsKey("attribution_id"))
        assertEquals(null, withoutMetadata["attribution_id"])
        assertFalse(withoutMetadata.containsKey("metadata"))
    }

    @Test
    fun `both bodies read the flag from the configuration`() {
        Configuration.instance = Configuration(
            "pk_test", "https://example.invalid", debug = true, environment = "staging",
        )
        assertTrue(AttributionApi.currentDebug())

        val matchBody = Attribution.buildMatchBody(signals(), "device-abc", AttributionApi.currentDebug())
        val eventBody = Attribution.buildEventBody("purchase", null, null, AttributionApi.currentDebug())

        assertEquals(true, matchBody["debug"])
        assertEquals(true, eventBody["debug"])
    }

    @Test
    fun `an unconfigured SDK reports live`() {
        Configuration.instance = null

        assertFalse(AttributionApi.currentDebug())
    }
}
