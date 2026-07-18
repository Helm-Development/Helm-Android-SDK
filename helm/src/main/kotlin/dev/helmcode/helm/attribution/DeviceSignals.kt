package dev.helmcode.helm.attribution

import android.content.Context
import android.os.Build
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Collects the device signals used for probabilistic fingerprint matching.
 *
 * These are the ONLY signals the Helm backend scores
 * (apps/tracking_links/services.py, MATCH_THRESHOLD = 9 of 11):
 *
 * | signal                          | points |
 * |---------------------------------|--------|
 * | screen_width AND screen_height  | 3      |
 * | device_pixel_ratio              | 2      |
 * | timezone                        | 2      |
 * | locale                          | 2      |
 * | os_version                      | 2      |
 *
 * IP address and user agent are deliberately NOT scored, so they must not be
 * sent -- Safari clicks arrive over IPv6 while the SDK uses IPv4.
 *
 * Every value is compared for EXACT equality against what the web click beacon
 * recorded (tracking_links/templates/tracking_links/interstitial.html), so the
 * units and formatting below must mirror the browser:
 *
 * - `screen.width` / `screen.height` are CSS pixels, not physical pixels.
 * - `window.devicePixelRatio` is persisted into a
 *   `DecimalField(max_digits=3, decimal_places=1)`, so a browser's 2.75 is
 *   stored as 2.8. We must round to 1 decimal place or we never compare equal.
 * - `Intl.DateTimeFormat().resolvedOptions().timeZone` is an IANA id.
 * - `navigator.language` is BCP-47 with a hyphen ("en-US", never "en_US").
 *
 * Mirrors the iOS SDK's `DeviceSignals`.
 */
internal data class DeviceSignals(
    val screenWidth: Int,
    val screenHeight: Int,
    /** Already rounded to 1 decimal place and formatted with [Locale.US], e.g. "2.8". */
    val devicePixelRatio: String,
    val timezone: String,
    val locale: String,
    val osVersion: String,
) {

    /** Convert to the JSON body shape the backend's match endpoint expects. */
    fun toMap(): Map<String, Any?> = mapOf(
        "screen_width" to screenWidth,
        "screen_height" to screenHeight,
        "device_pixel_ratio" to devicePixelRatio,
        "timezone" to timezone,
        "locale" to locale,
        "os_version" to osVersion,
    )

    companion object {

        /** Collect the signals for the current device. */
        fun collect(context: Context): DeviceSignals {
            val metrics = context.applicationContext.resources.displayMetrics
            return from(
                widthPixels = metrics.widthPixels,
                heightPixels = metrics.heightPixels,
                density = metrics.density,
                timezoneId = TimeZone.getDefault().id,
                localeTag = Locale.getDefault().toLanguageTag(),
                osVersion = Build.VERSION.RELEASE ?: "",
            )
        }

        /**
         * Pure signal construction, split out from [collect] so it is unit
         * testable without an Android runtime.
         */
        fun from(
            widthPixels: Int,
            heightPixels: Int,
            density: Float,
            timezoneId: String,
            localeTag: String,
            osVersion: String,
        ): DeviceSignals = DeviceSignals(
            screenWidth = toCssPixels(widthPixels, density),
            screenHeight = toCssPixels(heightPixels, density),
            devicePixelRatio = formatPixelRatio(density),
            timezone = timezoneId,
            locale = localeTag,
            osVersion = osVersion,
        )

        /**
         * Convert physical pixels to the density-independent CSS pixels a
         * browser reports via `screen.width` / `screen.height`.
         */
        fun toCssPixels(physicalPixels: Int, density: Float): Int {
            val safeDensity = if (density > 0f) density else 1f
            return (physicalPixels / safeDensity).roundToInt()
        }

        /**
         * Round the density to 1 decimal place to match the backend's
         * `DecimalField(max_digits=3, decimal_places=1)` storage, formatted
         * with [Locale.US] so locales like de-DE do not emit "2,8".
         */
        fun formatPixelRatio(density: Float): String {
            val safeDensity = if (density > 0f) density else 1f
            return String.format(Locale.US, "%.1f", safeDensity)
        }
    }
}
