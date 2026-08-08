package dev.helmcode.helm

import android.content.Context
import dev.helmcode.helm.analytics.Analytics
import dev.helmcode.helm.attribution.Attribution

/**
 * Main entry point for the Helm Android SDK.
 *
 * Usage:
 * ```
 * Helm.configure(context, publishableKey = "pk_...", baseURL = "https://helmcode.dev")
 * Helm.attribution.match(context)
 * ```
 */
object Helm {

    /**
     * Configure the SDK with your publishable key and API base URL.
     * Must be called before using any other SDK features.
     *
     * Prefer the [configure] overload that takes a `Context`: the influencer
     * attribution methods ([Attribution.submitPromoCode],
     * [Attribution.fetchAttributionStatus],
     * [Attribution.submitOriginalTransactionId]) need persistence for their
     * offline queue and status cache, and report `"not_configured"` until a
     * Context is bound. `Helm.attribution.match(context)` binds one too.
     *
     * @param publishableKey Your Helm publishable key (starts with "pk_")
     * @param baseURL The base URL of your Helm API (e.g. "https://helmcode.dev")
     */
    fun configure(publishableKey: String, baseURL: String) {
        Configuration.instance = Configuration(publishableKey, baseURL)
    }

    /**
     * Configure the SDK and bind the attribution module's persistence.
     *
     * This is the form to call: binding a Context enables the influencer
     * attribution methods, and configuring is itself one of the three triggers
     * that replay the offline submission queue (the others are app foregrounding
     * and the next attribution call).
     *
     * Any `userId` later handed to the attribution methods is passed to the
     * backend verbatim and must equal the app's RevenueCat app user ID -- that
     * identifier is what joins a Helm influencer link to the customer's
     * subscription.
     *
     * @param context Android context (Activity or Application); only the
     *   application context is retained.
     * @param publishableKey Your Helm publishable key (starts with "pk_")
     * @param baseURL The base URL of your Helm API (e.g. "https://helmcode.dev")
     */
    fun configure(context: Context, publishableKey: String, baseURL: String) {
        Configuration.instance = Configuration(publishableKey, baseURL)
        Attribution.instance.bind(context)
    }

    /**
     * Access the attribution tracking module.
     */
    val attribution: Attribution
        get() = Attribution.instance

    /**
     * Access the product analytics module. Call Helm.analytics.start(context)
     * after configure to begin installation/session/event tracking.
     */
    val analytics: Analytics
        get() = Analytics.instance
}
