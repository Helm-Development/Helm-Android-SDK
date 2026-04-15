package dev.helmcode.helm

/**
 * Internal configuration holder for the Helm SDK.
 */
internal data class Configuration(
    val publishableKey: String,
    val baseURL: String
) {
    companion object {
        var instance: Configuration? = null
    }
}
