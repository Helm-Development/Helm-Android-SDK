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

    fun registrationBody(installationId: String, userHash: String, device: DeviceFacts): Map<String, Any?> = mapOf(
        "installation_id" to installationId,
        "platform" to device.platform,
        "app_version" to device.appVersion,
        "os_version" to device.osVersion,
        "locale" to device.locale,
        "timezone" to device.timezone,
        "user_hash" to userHash,
    )

    fun eventsBody(installationId: String, events: List<AnalyticsEvent>): Map<String, Any?> = mapOf(
        "installation_id" to installationId,
        "events" to events.map { it.payload() },
    )

    // network

    /**
     * Register (or re-register) this installation. userHash may be "" when anonymous;
     * the SDK always echoes its stored hash so identity never regresses (spec §5).
     */
    suspend fun registerInstallation(installationId: String, userHash: String, device: DeviceFacts) {
        HelmHttpClient.post(
            path = "/api/v1/analytics/installations/",
            body = registrationBody(installationId, userHash, device),
        )
    }

    suspend fun sendEvents(installationId: String, events: List<AnalyticsEvent>) {
        HelmHttpClient.post(
            path = "/api/v1/analytics/events/",
            body = eventsBody(installationId, events),
        )
    }
}
