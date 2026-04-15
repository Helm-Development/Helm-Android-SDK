package dev.helmcode.helm

import dev.helmcode.helm.networking.HelmError
import dev.helmcode.helm.networking.HelmHttpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for HelmHttpClient.
 */
class HelmHttpClientTest {

    @Before
    fun setUp() {
        // Ensure SDK is not configured before each test
        Configuration.instance = null
    }

    @Test
    fun `post throws NotConfigured when Configuration instance is null`() = runBlocking {
        try {
            HelmHttpClient.post("/test/", mapOf("key" to "value"))
            fail("Expected HelmError.NotConfigured to be thrown")
        } catch (e: HelmError.NotConfigured) {
            // Expected
            assertTrue(e.message.contains("not configured"))
        } catch (e: Exception) {
            fail("Expected HelmError.NotConfigured but got ${e::class.simpleName}: ${e.message}")
        }
    }
}
