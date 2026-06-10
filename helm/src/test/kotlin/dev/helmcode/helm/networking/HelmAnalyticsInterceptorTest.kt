package dev.helmcode.helm.networking

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class HelmAnalyticsInterceptorTest {

    @Test
    fun stampsHeadersFromProvider() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()

        val client = OkHttpClient.Builder()
            .addInterceptor(HelmAnalyticsInterceptor(headersProvider = {
                mapOf(
                    "X-Helm-Installation-Id" to "iid-123",
                    "X-Helm-Session-Id" to "sid-456",
                )
            }))
            .build()
        client.newCall(Request.Builder().url(server.url("/api/")).build()).execute()

        val recorded = server.takeRequest()
        assertEquals("iid-123", recorded.getHeader("X-Helm-Installation-Id"))
        assertEquals("sid-456", recorded.getHeader("X-Helm-Session-Id"))
        assertNotNull(recorded)
        server.shutdown()
    }
}
