package dev.helmcode.helm.attribution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Guards the fingerprint match payload contract (HELM-215).
 *
 * The backend (apps/tracking_links/services.py) scores ONLY screen_width +
 * screen_height, device_pixel_ratio, timezone, locale and os_version, for 11
 * points total against a MATCH_THRESHOLD of 9. It scores neither `ip` nor
 * `user_agent`, so a payload built from those always scored 0 and never
 * matched. Every value is compared for exact equality against what the web
 * click beacon recorded, so units and formatting must mirror the browser.
 */
class DeviceSignalsTest {

    private fun signals() = DeviceSignals.from(
        widthPixels = 1080,
        heightPixels = 2340,
        density = 2.75f,
        timezoneId = "America/New_York",
        localeTag = "en-US",
        osVersion = "14",
    )

    @Test
    fun `match body contains exactly the scored signals plus device id`() {
        val body = Attribution.buildMatchBody(signals(), "device-abc")

        assertEquals(
            setOf(
                "screen_width",
                "screen_height",
                "device_pixel_ratio",
                "timezone",
                "locale",
                "os_version",
                "device_id",
            ),
            body.keys,
        )
    }

    @Test
    fun `match body never sends unscored ip or user agent`() {
        val body = Attribution.buildMatchBody(signals(), "device-abc")

        assertFalse("ip is not scored by the backend", body.containsKey("ip"))
        assertFalse("user_agent is not scored by the backend", body.containsKey("user_agent"))
    }

    @Test
    fun `match body carries the device id used to stamp the attribution`() {
        val body = Attribution.buildMatchBody(signals(), "device-abc")

        assertEquals("device-abc", body["device_id"])
    }

    @Test
    fun `screen dimensions are css pixels not physical pixels`() {
        // A browser on a 1080x2340 physical / 2.75 density device reports
        // roughly 393x851 CSS pixels via screen.width / screen.height.
        val s = signals()

        assertEquals(393, s.screenWidth)
        assertEquals(851, s.screenHeight)
    }

    @Test
    fun `device pixel ratio is rounded to one decimal place`() {
        // The server column is DecimalField(decimal_places=1), so a browser's
        // 2.75 is persisted as 2.8. Sending 2.75 would never compare equal.
        assertEquals("2.8", DeviceSignals.formatPixelRatio(2.75f))
        assertEquals("3.0", DeviceSignals.formatPixelRatio(3.0f))
        assertEquals("1.5", DeviceSignals.formatPixelRatio(1.5f))
    }

    @Test
    fun `device pixel ratio uses a dot separator in comma decimal locales`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("2.8", DeviceSignals.formatPixelRatio(2.75f))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `device pixel ratio serializes as a plain decimal`() {
        val value = signals().toMap()["device_pixel_ratio"].toString()

        assertEquals("2.8", value)
        assertFalse("must not use scientific notation", value.contains("E", ignoreCase = true))
    }

    @Test
    fun `passthrough signals are unmodified`() {
        val s = signals()

        assertEquals("America/New_York", s.timezone)
        assertEquals("en-US", s.locale)
        assertEquals("14", s.osVersion)
    }

    @Test
    fun `locale is hyphenated bcp47 not underscored`() {
        val tag = Locale.US.toLanguageTag()

        assertEquals("en-US", tag)
        assertTrue(DeviceSignals.from(1, 1, 1f, "UTC", tag, "14").locale.contains("-"))
    }

    @Test
    fun `a zero density does not divide by zero`() {
        val s = DeviceSignals.from(1080, 2340, 0f, "UTC", "en-US", "14")

        assertEquals(1080, s.screenWidth)
        assertEquals(2340, s.screenHeight)
        assertEquals("1.0", s.devicePixelRatio)
    }
}
