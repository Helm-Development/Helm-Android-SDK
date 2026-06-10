package dev.helmcode.helm.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class InMemoryStore : KeyValueStore {
    private val map = mutableMapOf<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

class StoresTest {

    @Test
    fun installationIdIsLowercaseUuidGeneratedOnce() {
        val store = InstallationStore(InMemoryStore())
        val id = store.installationId()
        assertNotNull(UUID.fromString(id))
        assertEquals(id, id.lowercase())
        assertEquals(id, store.installationId())
    }

    @Test
    fun installationIdPersistsAcrossInstances() {
        val backing = InMemoryStore()
        assertEquals(InstallationStore(backing).installationId(),
                     InstallationStore(backing).installationId())
    }

    @Test
    fun identityRoundTripAndClear() {
        val store = IdentityStore(InMemoryStore())
        assertNull(store.userHash())
        store.store("a".repeat(32))
        assertEquals("a".repeat(32), store.userHash())
        store.clear()
        assertNull(store.userHash())
    }
}
