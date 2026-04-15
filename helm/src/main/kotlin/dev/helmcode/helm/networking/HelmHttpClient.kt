package dev.helmcode.helm.networking

import dev.helmcode.helm.Configuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Internal HTTP client for Helm SDK API calls.
 * Uses HttpURLConnection to avoid external dependencies.
 */
internal object HelmHttpClient {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /**
     * Perform a POST request to the Helm API.
     *
     * @param path The API path (e.g. "/attribution/match/")
     * @param body The request body as a map. Values can be String, Number, Boolean, null, or nested Maps/Lists.
     * @return The parsed JSON response as a Map.
     * @throws HelmError on any failure.
     */
    suspend fun post(path: String, body: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.IO) {
        val config = Configuration.instance
            ?: throw HelmError.NotConfigured

        val urlString = config.baseURL.trimEnd('/') + path
        val url = URL(urlString)

        val connection: HttpURLConnection
        try {
            connection = url.openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw HelmError.NetworkError(e)
        }

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${config.publishableKey}")

            // Write request body
            val jsonBody = JSONObject(body).toString()
            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(jsonBody)
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode

            if (responseCode in 200..299) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val responseText = reader.readText()
                reader.close()

                try {
                    jsonToMap(JSONObject(responseText))
                } catch (e: Exception) {
                    throw HelmError.InvalidResponse
                }
            } else {
                val errorReader = try {
                    BufferedReader(InputStreamReader(connection.errorStream ?: connection.inputStream))
                } catch (_: Exception) {
                    null
                }
                val errorBody = errorReader?.readText() ?: ""
                errorReader?.close()

                throw HelmError.ServerError(responseCode, errorBody)
            }
        } catch (e: HelmError) {
            throw e
        } catch (e: Exception) {
            throw HelmError.NetworkError(e)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Convert a JSONObject to a Map<String, Any?>.
     */
    private fun jsonToMap(json: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.get(key)
            map[key] = when (value) {
                is JSONObject -> jsonToMap(value)
                JSONObject.NULL -> null
                else -> value
            }
        }
        return map
    }
}
