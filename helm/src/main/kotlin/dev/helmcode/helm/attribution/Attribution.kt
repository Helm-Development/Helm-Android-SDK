package dev.helmcode.helm.attribution

import android.content.Context
import android.util.Log
import dev.helmcode.helm.analytics.Analytics
import dev.helmcode.helm.networking.HelmHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles attribution matching and event tracking for the Helm SDK.
 *
 * On first launch, [match] attempts to attribute the install via:
 * 1. Google Play Install Referrer (if utm_source=helm)
 * 2. Fingerprint matching (screen size, pixel ratio, timezone, locale, OS version)
 *
 * After attribution, use [increment] to track conversion events.
 */
class Attribution internal constructor() {

    companion object {
        private const val TAG = "HelmSDK"

        internal val instance: Attribution by lazy { Attribution() }

        // Backend routes (apps/client_api/urls.py, included at /api/). Must match
        // the iOS SDK and the Helm backend exactly, or attribution requests 404.
        internal const val PATH_REFERRER = "/api/client/v1/attribution/referrer/"
        internal const val PATH_MATCH = "/api/client/v1/attribution/match/"
        internal const val PATH_EVENT = "/api/client/v1/attribution/event/"

        /**
         * Build the POST body for [PATH_MATCH]: the six scored fingerprint
         * signals plus the device id the server stamps onto the Attribution.
         *
         * Deliberately contains no "ip" or "user_agent" -- the backend does not
         * score either, and the IP lookup was an external round trip whose
         * failure aborted the whole match.
         */
        internal fun buildMatchBody(signals: DeviceSignals, deviceId: String): Map<String, Any?> =
            signals.toMap() + mapOf("device_id" to deviceId)
    }

    /**
     * Cached application context for use by increment() which has no Context parameter.
     * Set during match() so that subsequent increment() calls can read SharedPreferences.
     */
    @Volatile
    private var appContext: Context? = null

    /**
     * Attempt to match this device to a Helm tracking link.
     * Safe to call on every app launch -- it no-ops if already matched.
     *
     * @param context Android context (Activity or Application)
     */
    fun match(context: Context) {
        try {
            appContext = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    performMatch(context.applicationContext)
                } catch (e: Exception) {
                    Log.w(TAG, "Attribution match failed: ${e.message}", e)
                    // Do NOT mark as checked -- allow retry on next launch
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch attribution match: ${e.message}", e)
        }
    }

    /**
     * Track a conversion event.
     *
     * @param eventType The type of event (e.g. "signup", "purchase", "subscription")
     * @param metadata Optional key-value metadata to attach to the event
     */
    fun increment(eventType: String, metadata: Map<String, Any>? = null) {
        try {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    performIncrement(eventType, metadata)
                } catch (e: Exception) {
                    Log.w(TAG, "Attribution increment failed: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch attribution increment: ${e.message}", e)
        }
    }

    // ------------------------------------------------------------------
    // Internal implementation
    // ------------------------------------------------------------------

    private suspend fun performMatch(context: Context) {
        if (AttributionStore.hasChecked(context)) {
            return
        }

        val deviceId = AttributionStore.getOrCreateDeviceId(context)

        // 1. Try install referrer
        val referrerResult = try {
            InstallReferrerReader.readReferrer(context)
        } catch (e: Exception) {
            Log.w(TAG, "Install referrer read failed: ${e.message}", e)
            null
        }

        if (referrerResult != null) {
            // Referrer matched Helm -- POST to /api/client/v1/attribution/referrer/
            val body = mutableMapOf<String, Any?>(
                "tracking_link_id" to referrerResult.trackingLinkId,
                "device_id" to deviceId
            )
            referrerResult.tokenSlug?.let { body["referral_token_slug"] = it }

            val response = HelmHttpClient.post(PATH_REFERRER, body)
            val attributionId = response["attribution_id"] as? String
            if (attributionId != null) {
                AttributionStore.storeMatch(context, attributionId)
                Analytics.instance.onAttributionMatched(attributionId)
            } else {
                AttributionStore.storeUnmatched(context)
            }
            return
        }

        // 2. Fingerprint path
        //
        // Only the six signals the backend actually scores are sent. IP and
        // user agent are NOT scored server-side (Safari clicks over IPv6, the
        // SDK over IPv4), so sending them scored 0 and never matched.
        val body = buildMatchBody(DeviceSignals.collect(context), deviceId)

        val response = HelmHttpClient.post(PATH_MATCH, body)
        val attributionId = response["attribution_id"] as? String

        if (!attributionId.isNullOrEmpty()) {
            AttributionStore.storeMatch(context, attributionId)
            Analytics.instance.onAttributionMatched(attributionId)
        } else {
            AttributionStore.storeUnmatched(context)
        }
    }

    private suspend fun performIncrement(
        eventType: String,
        metadata: Map<String, Any>?
    ) {
        val body = mutableMapOf<String, Any?>(
            "event_type" to eventType
        )

        if (metadata != null) {
            body["metadata"] = metadata
        }

        // Read attribution ID from store if context is available
        val ctx = appContext
        val attributionId = if (ctx != null) {
            AttributionStore.getAttributionId(ctx)
        } else {
            null
        }
        body["attribution_id"] = attributionId

        HelmHttpClient.post(PATH_EVENT, body)
    }
}
