package dev.helmcode.helm.networking

/**
 * Errors that can occur during Helm SDK network operations.
 */
internal sealed class HelmError : Exception() {

    /** SDK has not been configured. Call Helm.configure() first. */
    object NotConfigured : HelmError() {
        private fun readResolve(): Any = NotConfigured
        override val message: String = "Helm SDK is not configured. Call Helm.configure() first."
    }

    /** A network-level error occurred. */
    data class NetworkError(override val cause: Throwable) : HelmError() {
        override val message: String = "Network error: ${cause.message}"
    }

    /** The server returned an unparseable response. */
    object InvalidResponse : HelmError() {
        private fun readResolve(): Any = InvalidResponse
        override val message: String = "Invalid response from server."
    }

    /** The server returned an error status code. */
    data class ServerError(val code: Int, override val message: String) : HelmError()
}
