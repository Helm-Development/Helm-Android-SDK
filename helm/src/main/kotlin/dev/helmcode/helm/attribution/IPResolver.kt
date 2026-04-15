package dev.helmcode.helm.attribution

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves the device's public IP address using ipify.
 */
internal object IPResolver {

    private const val IP_SERVICE_URL = "https://api.ipify.org?format=json"
    private const val TIMEOUT_MS = 10_000

    /**
     * Fetch the device's public IP address.
     *
     * @return The public IP address as a string.
     * @throws Exception if the request fails or the response cannot be parsed.
     */
    suspend fun fetchPublicIP(): String = withContext(Dispatchers.IO) {
        val url = URL(IP_SERVICE_URL)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("IP resolution failed with code $responseCode")
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()

            val json = JSONObject(response)
            json.getString("ip")
        } finally {
            connection.disconnect()
        }
    }
}
