package dev.helmcode.helm.analytics

import android.content.Context
import android.os.Build
import dev.helmcode.helm.networking.HelmHttpClient
import java.util.Locale
import java.util.TimeZone

/**
 * Static facts about this device/app build, captured once at start().
 * A plain data holder so payload builders stay pure and unit-testable.
 */
internal data class DeviceFacts(
    val platform: String,
    val appVersion: String,
    val osVersion: String,
    val locale: String,
    val timezone: String,
) {
    companion object {
        fun collect(context: Context): DeviceFacts {
            val appVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            } catch (_: Exception) {
                "unknown"
            }
            return DeviceFacts(
                platform = "android",
                appVersion = appVersion,
                osVersion = Build.VERSION.RELEASE ?: "unknown",
                locale = Locale.getDefault().toLanguageTag(), // hyphenated, e.g. en-US
                timezone = TimeZone.getDefault().id,
            )
        }
    }
}

/**
 * Builds and sends analytics payloads to Helm via HelmHttpClient.
 */
internal object AnalyticsClient {

    // payload builders (pure, unit-tested)

    fun registrationBody(
        installationId: String,
        userHash: String,
        device: DeviceFacts,
        attributionToken: String? = null,
    ): Map<String, Any?> = buildMap {
        put("installation_id", installationId)
        put("platform", device.platform)
        put("app_version", device.appVersion)
        put("os_version", device.osVersion)
        put("locale", device.locale)
        put("timezone", device.timezone)
        put("user_hash", userHash)
        attributionToken?.takeIf { it.isNotEmpty() }?.let { put("attribution_token", it) }
    }

    fun eventsBody(installationId: String, events: List<AnalyticsEvent>): Map<String, Any?> = mapOf(
        "installation_id" to installationId,
        "events" to events.map { it.payload() },
    )

    // network

    /**
     * Register (or re-register) this installation. userHash may be "" when anonymous;
     * the SDK always echoes its stored hash so identity never regresses (spec §5).
     */
    suspend fun registerInstallation(
        installationId: String,
        userHash: String,
        device: DeviceFacts,
        attributionToken: String? = null,
    ) {
        HelmHttpClient.post(
            path = "/api/v1/analytics/installations/",
            body = registrationBody(installationId, userHash, device, attributionToken),
        )
    }

    suspend fun sendEvents(installationId: String, events: List<AnalyticsEvent>) {
        HelmHttpClient.post(
            path = "/api/v1/analytics/events/",
            body = eventsBody(installationId, events),
        )
    }
}
