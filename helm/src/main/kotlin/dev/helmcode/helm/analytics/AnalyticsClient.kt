package dev.helmcode.helm.analytics

import android.content.Context
import android.os.Build
import dev.helmcode.helm.Configuration
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
        debug: Boolean = false,
        environment: String = "production",
    ): Map<String, Any?> = buildMap {
        put("installation_id", installationId)
        put("platform", device.platform)
        put("app_version", device.appVersion)
        put("os_version", device.osVersion)
        put("locale", device.locale)
        put("timezone", device.timezone)
        put("user_hash", userHash)
        // HELM-238: the same `debug` wire field the attribution endpoints take
        // (TAS-801). Helm leaves activity from a debug build out of its
        // active-user counts. Always sent, including when false, so a device that
        // moves from a debug build to a shipped one counts as live again.
        put("debug", debug)
        // HELM-242: the deployment environment this build was configured with.
        // Sent alongside `debug` but separate from it: `debug` says whether the
        // activity counts as real usage, `environment` only says which
        // deployment it came from.
        put("environment", environment)
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
     * The configured `debug` flag rides along so Helm can tell a developer's build
     * apart from a real user (HELM-238), and the configured `environment` label
     * so activity can be filtered by deployment (HELM-242).
     */
    suspend fun registerInstallation(
        installationId: String,
        userHash: String,
        device: DeviceFacts,
        attributionToken: String? = null,
    ) {
        HelmHttpClient.post(
            path = "/api/v1/analytics/installations/",
            body = registrationBody(
                installationId,
                userHash,
                device,
                attributionToken,
                debug = Configuration.instance?.debug ?: false,
                environment = Configuration.instance?.environment ?: "production",
            ),
        )
    }

    suspend fun sendEvents(installationId: String, events: List<AnalyticsEvent>) {
        HelmHttpClient.post(
            path = "/api/v1/analytics/events/",
            body = eventsBody(installationId, events),
        )
    }
}
