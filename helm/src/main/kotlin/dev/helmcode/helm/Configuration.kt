package dev.helmcode.helm

/**
 * Internal configuration holder for the Helm SDK.
 *
 * @param debug when true, every influencer-attribution submission this SDK
 *   makes is registered in Helm as sandbox test data, and analytics activity
 *   from this build is left out of Helm's active-user counts. Defaulted so the
 *   pre-0.6.0 two-argument construction keeps working unchanged.
 * @param environment HELM-242: free-form label for the build's deployment
 *   environment, recorded on every analytics event. Independent of [debug].
 *   Defaulted so existing construction keeps working unchanged.
 */
internal data class Configuration(
    val publishableKey: String,
    val baseURL: String,
    val debug: Boolean = false,
    val environment: String = "production",
) {
    companion object {
        @Volatile var instance: Configuration? = null
    }
}
