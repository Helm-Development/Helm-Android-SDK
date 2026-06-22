package dev.helmcode.helm.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.UUID

class DeviceIdStoreTest {

    @Test
    fun adoptsExistingInstallationIdVerbatim() {
        val backing = InMemoryStore().apply { put("helm_installation_id", "existing-id-123") }
        val id = DeviceIdStore(backing) { "legacy-attr-id" }.deviceId()
        assertEquals("existing-id-123", id)
    }

    @Test
    fun seedsFromLegacyAttributionIdWhenNoInstallationId() {
        val backing = InMemoryStore()
        val id = DeviceIdStore(backing) { "legacy-attr-id" }.deviceId()
        assertEquals("legacy-attr-id", id)
        assertEquals("legacy-attr-id", backing.get("helm_installation_id"))
    }

    @Test
    fun mintsLowercaseUuidWhenNothingPresent() {
        val backing = InMemoryStore()
        val id = DeviceIdStore(backing).deviceId()
        assertNotNull(UUID.fromString(id))
        assertEquals(id, id.lowercase())
    }

    @Test
    fun isIdempotentAcrossReads() {
        val backing = InMemoryStore()
        val store = DeviceIdStore(backing)
        assertEquals(store.deviceId(), store.deviceId())
    }

    @Test
    fun persistsAcrossInstancesSharingBacking() {
        val backing = InMemoryStore()
        assertEquals(DeviceIdStore(backing).deviceId(), DeviceIdStore(backing).deviceId())
    }
}
