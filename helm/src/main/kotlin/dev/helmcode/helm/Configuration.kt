package dev.helmcode.helm

/**
 * Internal configuration holder for the Helm SDK.
 *
 * @param debug when true, every influencer-attribution submission this SDK
 *   makes is registered in Helm as sandbox test data. Defaulted so the
 *   pre-0.6.0 two-argument construction keeps working unchanged.
 */
internal data class Configuration(
    val publishableKey: String,
    val baseURL: String,
    val debug: Boolean = false,
) {
    companion object {
        @Volatile var instance: Configuration? = null
    }
}
