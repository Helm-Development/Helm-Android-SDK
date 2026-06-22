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

    @Test
    fun concurrentFirstReadsAcrossInstancesYieldSameId() {
        val backing = InMemoryStore()
        val a = DeviceIdStore(backing)
        val b = DeviceIdStore(backing)
        val results = java.util.Collections.synchronizedList(mutableListOf<String>())
        val latch = java.util.concurrent.CountDownLatch(1)
        val t1 = Thread { latch.await(); results.add(a.deviceId()) }
        val t2 = Thread { latch.await(); results.add(b.deviceId()) }
        t1.start(); t2.start()
        latch.countDown()
        t1.join(); t2.join()
        assertEquals("both instances must resolve the same id", 1, results.toSet().size)
    }
}
