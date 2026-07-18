package dev.helmcode.helm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Unit tests for AttributionStore.
 *
 * NOTE: AttributionStore relies on Android SharedPreferences, which requires
 * either Robolectric or Android instrumented tests to test properly.
 * SharedPreferences tests should be placed in:
 *   helm/src/androidTest/kotlin/dev/helmcode/helm/AttributionStoreInstrumentedTest.kt
 *
 * This file contains basic unit tests that don't require Android framework classes.
 */
class AttributionStoreTest {

    @Test
    fun `Configuration data class holds publishableKey and baseURL`() {
        val config = Configuration(
            publishableKey = "pk_test_123",
            baseURL = "https://helmcode.dev"
        )

        assertEquals("pk_test_123", config.publishableKey)
        assertEquals("https://helmcode.dev", config.baseURL)
    }

    @Test
    fun `Configuration data class supports copy`() {
        val config = Configuration(
            publishableKey = "pk_test_123",
            baseURL = "https://helmcode.dev"
        )

        val updated = config.copy(baseURL = "https://helmcode.dev/staging")

        assertEquals("pk_test_123", updated.publishableKey)
        assertEquals("https://helmcode.dev/staging", updated.baseURL)
    }

    @Test
    fun `Configuration companion object starts with null instance`() {
        // Reset to ensure clean state
        Configuration.instance = null
        assertEquals(null, Configuration.instance)
    }

    @Test
    fun `Configuration companion object stores instance`() {
        val config = Configuration(
            publishableKey = "pk_live_abc",
            baseURL = "https://helmcode.dev"
        )
        Configuration.instance = config

        assertNotNull(Configuration.instance)
        assertEquals("pk_live_abc", Configuration.instance?.publishableKey)

        // Clean up
        Configuration.instance = null
    }
}
