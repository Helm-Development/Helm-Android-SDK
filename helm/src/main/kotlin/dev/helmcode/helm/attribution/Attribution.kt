package dev.helmcode.helm.attribution

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.helmcode.helm.AttributionStatus
import dev.helmcode.helm.HelmResult
import dev.helmcode.helm.PromoCodeLink
import dev.helmcode.helm.analytics.Analytics
import dev.helmcode.helm.analytics.SharedPrefsStore
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
 *
 * ## Influencer attribution (HELM-221)
 *
 * [submitPromoCode], [fetchAttributionStatus], and [submitOriginalTransactionId]
 * link a customer to an influencer promo code and read that link back. All three
 * need a bound Context -- call `Helm.configure(context, publishableKey, baseURL)`
 * (or [match], which binds too) before using them, or they return
 * `HelmResult.Failure(code = "not_configured")`.
 *
 * Every `userId` on these calls is an **opaque string passed through verbatim**:
 * the SDK does not validate, trim, or normalize it. It must be the same value the
 * app uses as its RevenueCat app user ID, because that identifier is what joins
 * the Helm link to the customer's subscription on the server side.
 *
 * ## Sandbox submissions (TAS-801)
 *
 * All three calls send the `debug` flag given to `Helm.configure(...)`. With
 * `debug = true` the resulting link and transaction lineage are marked sandbox
 * test data in Helm and are excluded from every payout and billing figure. A
 * submission queued while offline replays with the marker it was **made** under,
 * so relabelling cannot happen across a build change.
 *
 * As of HELM-242 the older [match] and [increment] requests send `debug` too, so
 * every attribution request this SDK makes carries it.
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

        // HELM-221 influencer attribution routes. All POST, all trailing-slash.
        internal const val PATH_PROMO_CODE = "/api/client/v1/attribution/promo-code/"
        internal const val PATH_STATUS = "/api/client/v1/attribution/status/"
        internal const val PATH_TRANSACTION = "/api/client/v1/attribution/transaction/"

        /**
         * Build the POST body for [PATH_MATCH]: the six scored fingerprint
         * signals plus the device id the server stamps onto the Attribution.
         *
         * Deliberately contains no "ip" or "user_agent" -- the backend does not
         * score either, and the IP lookup was an external round trip whose
         * failure aborted the whole match.
         *
         * HELM-242: `debug` is always present, as a real JSON boolean, the same
         * way the three influencer endpoints send it. `environment` is not sent
         * here -- it is an analytics-only field.
         */
        internal fun buildMatchBody(
            signals: DeviceSignals,
            deviceId: String,
            debug: Boolean = false,
        ): Map<String, Any?> =
            signals.toMap() + mapOf("device_id" to deviceId, "debug" to debug)

        /**
         * Build the POST body for [PATH_EVENT]: the conversion event type, the
         * attribution it belongs to, and any caller metadata.
         *
         * `attribution_id` is always present and may be null -- an unmatched
         * install still reports its conversions. `metadata` is omitted when the
         * caller passes none.
         *
         * HELM-242: `debug` is always present, as a real JSON boolean, the same
         * way the three influencer endpoints send it. `environment` is not sent
         * here -- it is an analytics-only field.
         */
        internal fun buildEventBody(
            eventType: String,
            metadata: Map<String, Any>?,
            attributionId: String?,
            debug: Boolean = false,
        ): Map<String, Any?> = buildMap {
            put("event_type", eventType)
            put("debug", debug)
            if (metadata != null) put("metadata", metadata)
            put("attribution_id", attributionId)
        }
    }

    /**
     * Cached application context for use by increment() which has no Context parameter.
     * Set during match() so that subsequent increment() calls can read SharedPreferences.
     */
    @Volatile
    private var appContext: Context? = null

    /**
     * The influencer-attribution engine. Null until [bind]; the public
     * attribution methods report "not_configured" rather than throw while it is.
     */
    @Volatile
    private var api: AttributionApi? = null

    private val bindLock = Any()
    private var lifecycleObserved = false

    /**
     * Attempt to match this device to a Helm tracking link.
     * Safe to call on every app launch -- it no-ops if already matched.
     *
     * @param context Android context (Activity or Application)
     */
    fun match(context: Context) {
        try {
            bind(context)
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
    // Influencer attribution (HELM-221)
    // ------------------------------------------------------------------

    /**
     * Link a customer to an influencer promo code.
     *
     * Returns [HelmResult.Success] with the confirmed [PromoCodeLink],
     * [HelmResult.Failure] when the server rejects the code (`"invalid_code"`,
     * `"code_inactive"`, `"already_linked"`), or [HelmResult.Queued] when the
     * request could not be delivered.
     *
     * A queued submission is retried automatically for up to 30 days -- on the
     * next `Helm.configure(context, ...)`, the next app foregrounding, or before
     * the next attribution call. **Deferred failures are silent by design:** if
     * the replayed code turns out to be invalid, the entry is dropped without a
     * callback, and the app observes the real state through
     * [fetchAttributionStatus]. Show queued codes as pending, not linked.
     *
     * Never throws.
     *
     * @param userId opaque customer identifier, passed to the backend verbatim.
     *   Must equal the app's RevenueCat app user ID.
     * @param code the influencer promo code the customer entered.
     */
    suspend fun submitPromoCode(userId: String, code: String): HelmResult<PromoCodeLink> =
        api?.submitPromoCode(userId, code) ?: notConfigured()

    /**
     * Read the customer's current link state.
     *
     * Falls back to the locally cached status when the network is unavailable --
     * check [AttributionStatus.fromCache] before treating the value as fresh.
     * Returns `Failure("network_error")` only when there is no cache to serve,
     * and never returns [HelmResult.Queued] (a read has nothing to replay).
     *
     * Never throws.
     *
     * @param userId opaque customer identifier, passed to the backend verbatim.
     *   Must equal the app's RevenueCat app user ID.
     */
    suspend fun fetchAttributionStatus(userId: String): HelmResult<AttributionStatus> =
        api?.fetchAttributionStatus(userId) ?: notConfigured()

    /**
     * Report the store's original transaction id so Helm can join the
     * customer's subscription revenue to their influencer link.
     *
     * Queues on transport failure exactly like [submitPromoCode]; the endpoint
     * is an idempotent append, so a replayed duplicate is harmless.
     *
     * Never throws.
     *
     * @param userId opaque customer identifier, passed to the backend verbatim.
     *   Must equal the app's RevenueCat app user ID.
     * @param originalTransactionId the store's original transaction identifier
     *   for the subscription.
     */
    suspend fun submitOriginalTransactionId(
        userId: String,
        originalTransactionId: String,
    ): HelmResult<Unit> =
        api?.submitOriginalTransactionId(userId, originalTransactionId) ?: notConfigured()

    /**
     * Fire-and-forget [submitOriginalTransactionId] for callers with no
     * coroutine scope (e.g. a RevenueCat purchase listener). The result is
     * discarded; a transport failure still queues for replay.
     *
     * Named `...Async` rather than overloading [submitOriginalTransactionId]
     * because Kotlin rejects a suspend/non-suspend pair with identical value
     * parameters as conflicting overloads (KT-23610).
     */
    fun submitOriginalTransactionIdAsync(userId: String, originalTransactionId: String) {
        try {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    submitOriginalTransactionId(userId, originalTransactionId)
                } catch (e: Exception) {
                    Log.w(TAG, "Transaction id submission failed: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch transaction id submission: ${e.message}", e)
        }
    }

    /**
     * Drop all locally held influencer-attribution state: the pending-submission
     * queue and the cached statuses. Call on logout.
     *
     * Install-match state and the device id survive -- they describe the device,
     * not the customer.
     */
    fun reset() {
        api?.reset()
    }

    /**
     * Binds persistence and starts the replay machinery. Idempotent: the engine
     * and the foreground observer are created once per process.
     *
     * Called by `Helm.configure(context, ...)` and by [match], so integrations
     * that only ever call [match] still get queue replay.
     */
    internal fun bind(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        val observe = synchronized(bindLock) {
            if (api == null) {
                api = AttributionApi(
                    store = SharedPrefsStore(appCtx),
                    deviceId = { AttributionStore.getOrCreateDeviceId(appCtx) },
                    clock = System::currentTimeMillis,
                )
            }
            if (lifecycleObserved) {
                false
            } else {
                lifecycleObserved = true
                true
            }
        }
        // Registration and replay run OUTSIDE bindLock: one posts to the main
        // thread, the other hits the network.
        if (observe) observeForeground()
        replayInBackground()
    }

    /** Wipe on identity clear, driven by `Analytics.clearIdentity()`. */
    internal fun onIdentityCleared() {
        val engine = api
        if (engine == null) {
            Log.w(TAG, "onIdentityCleared() before bind -- nothing to wipe")
            return
        }
        engine.reset()
    }

    // ------------------------------------------------------------------
    // Internal implementation
    // ------------------------------------------------------------------

    /**
     * There is no Context, so there is nowhere to persist a retry: report and
     * drop rather than pretending the submission is queued.
     */
    private fun <T> notConfigured(): HelmResult<T> = HelmResult.Failure(
        code = "not_configured",
        message = "Call Helm.configure(context, publishableKey, baseURL) before using attribution methods.",
    )

    private fun replayInBackground() {
        val engine = api ?: return
        try {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    engine.replayPending()
                } catch (e: Exception) {
                    Log.w(TAG, "Attribution queue replay failed: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch attribution queue replay: ${e.message}", e)
        }
    }

    private fun observeForeground() {
        try {
            // LifecycleRegistry.addObserver enforces the main thread -- hop
            // defensively, exactly as Analytics.observeLifecycle does.
            Handler(Looper.getMainLooper()).post {
                ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) {
                        replayInBackground()
                    }
                })
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to observe foreground for attribution replay: ${e.message}", e)
        }
    }

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
        val body = buildMatchBody(DeviceSignals.collect(context), deviceId, AttributionApi.currentDebug())

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
        // Read attribution ID from store if context is available
        val ctx = appContext
        val attributionId = if (ctx != null) {
            AttributionStore.getAttributionId(ctx)
        } else {
            null
        }

        val body = buildEventBody(
            eventType = eventType,
            metadata = metadata,
            attributionId = attributionId,
            debug = AttributionApi.currentDebug(),
        )

        HelmHttpClient.post(PATH_EVENT, body)
    }
}
