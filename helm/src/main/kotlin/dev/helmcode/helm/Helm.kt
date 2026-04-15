package dev.helmcode.helm

import dev.helmcode.helm.attribution.Attribution

/**
 * Main entry point for the Helm Android SDK.
 *
 * Usage:
 * ```
 * Helm.configure(publishableKey = "pk_...", baseURL = "https://api.helmcode.dev")
 * Helm.attribution.match(context)
 * ```
 */
object Helm {

    /**
     * Configure the SDK with your publishable key and API base URL.
     * Must be called before using any other SDK features.
     *
     * @param publishableKey Your Helm publishable key (starts with "pk_")
     * @param baseURL The base URL of your Helm API (e.g. "https://api.helmcode.dev")
     */
    fun configure(publishableKey: String, baseURL: String) {
        Configuration.instance = Configuration(publishableKey, baseURL)
    }

    /**
     * Access the attribution tracking module.
     */
    val attribution: Attribution
        get() = Attribution.instance
}
