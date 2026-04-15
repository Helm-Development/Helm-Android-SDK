package dev.helmcode.helm.attribution

import android.content.Context
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.android.installreferrer.api.ReferrerDetails
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLDecoder
import kotlin.coroutines.resume

/**
 * Result from parsing a Helm install referrer.
 */
internal data class ReferrerResult(
    val trackingLinkId: String,
    val tokenSlug: String?
)

/**
 * Reads the Google Play Install Referrer and parses Helm attribution data.
 */
internal object InstallReferrerReader {

    private const val TAG = "HelmSDK"
    private const val TIMEOUT_MS = 5000L

    /**
     * Attempt to read the install referrer from Google Play.
     *
     * @return A [ReferrerResult] if the referrer came from Helm, or null otherwise.
     */
    suspend fun readReferrer(context: Context): ReferrerResult? {
        return withTimeoutOrNull(TIMEOUT_MS) {
            try {
                val referrerString = getReferrerString(context) ?: return@withTimeoutOrNull null
                parseHelmReferrer(referrerString)
            } catch (e: Exception) {
                Log.w(TAG, "Install referrer error: ${e.message}", e)
                null
            }
        }
    }

    /**
     * Connect to the Install Referrer service and retrieve the referrer string.
     */
    private suspend fun getReferrerString(context: Context): String? {
        return suspendCancellableCoroutine { continuation ->
            val client = InstallReferrerClient.newBuilder(context).build()

            continuation.invokeOnCancellation {
                try {
                    client.endConnection()
                } catch (_: Exception) {
                    // Ignore cleanup errors
                }
            }

            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    try {
                        when (responseCode) {
                            InstallReferrerClient.InstallReferrerResponse.OK -> {
                                val details: ReferrerDetails = client.installReferrer
                                val referrer = details.installReferrer
                                client.endConnection()
                                if (continuation.isActive) {
                                    continuation.resume(referrer)
                                }
                            }
                            else -> {
                                Log.w(TAG, "Install referrer response code: $responseCode")
                                client.endConnection()
                                if (continuation.isActive) {
                                    continuation.resume(null)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error reading install referrer: ${e.message}", e)
                        try { client.endConnection() } catch (_: Exception) {}
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                }

                override fun onInstallReferrerServiceDisconnected() {
                    Log.w(TAG, "Install referrer service disconnected")
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            })
        }
    }

    /**
     * Parse a referrer string for Helm attribution parameters.
     * Expected format: utm_source=helm&tracking_link_id=<uuid>&token=<slug>
     *
     * @return [ReferrerResult] if utm_source=helm and tracking_link_id is present, null otherwise.
     */
    private fun parseHelmReferrer(referrer: String): ReferrerResult? {
        val params = mutableMapOf<String, String>()

        try {
            val decoded = URLDecoder.decode(referrer, "UTF-8")
            decoded.split("&").forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = parts[1]
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse referrer string: ${e.message}", e)
            return null
        }

        // Only process if this is a Helm referrer
        if (params["utm_source"] != "helm") {
            return null
        }

        val trackingLinkId = params["tracking_link_id"] ?: return null
        val tokenSlug = params["token"]

        return ReferrerResult(
            trackingLinkId = trackingLinkId,
            tokenSlug = tokenSlug
        )
    }
}
