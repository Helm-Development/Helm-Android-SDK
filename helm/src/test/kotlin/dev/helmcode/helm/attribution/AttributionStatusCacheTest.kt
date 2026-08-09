package dev.helmcode.helm.attribution

import dev.helmcode.helm.analytics.InMemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttributionStatusCacheTest {

    private val now = 1_765_200_000_000L
    private val backing = InMemoryStore()
    private val cache = AttributionStatusCache(backing)

    @Test
    fun putThenGetRoundTrips() {
        cache.put("user-1", CachedStatus(linked = true, influencerCode = "anna", offeringId = "off_1", fetchedAtMs = now))

        val cached = cache.get("user-1")!!
        assertTrue(cached.linked)
        assertEquals("anna", cached.influencerCode)
        assertEquals("off_1", cached.offeringId)
        assertEquals(now, cached.fetchedAtMs)
    }

    @Test
    fun unlinkedStatusRoundTripsWithNullFields() {
        cache.put("user-1", CachedStatus(linked = false, influencerCode = null, offeringId = null, fetchedAtMs = now))

        val cached = cache.get("user-1")!!
        assertFalse(cached.linked)
        assertNull(cached.influencerCode)
        assertNull(cached.offeringId)
    }

    @Test
    fun entriesAreIsolatedPerUser() {
        cache.put("user-1", CachedStatus(true, "anna", "off_1", now))

        assertNull("another identity on the same device must not see this link", cache.get("user-2"))
        assertEquals("anna", cache.get("user-1")?.influencerCode)
    }

    @Test
    fun multipleUsersCoexist() {
        cache.put("user-1", CachedStatus(true, "anna", null, now))
        cache.put("user-2", CachedStatus(true, "bruno", "off_2", now))

        assertEquals("anna", cache.get("user-1")?.influencerCode)
        assertEquals("bruno", cache.get("user-2")?.influencerCode)
    }

    @Test
    fun putOverwritesTheSameUser() {
        cache.put("user-1", CachedStatus(false, null, null, now))
        cache.put("user-1", CachedStatus(true, "anna", "off_1", now + 1000))

        val cached = cache.get("user-1")!!
        assertTrue(cached.linked)
        assertEquals("anna", cached.influencerCode)
        assertEquals(now + 1000, cached.fetchedAtMs)
    }

    @Test
    fun clearWipesEveryUser() {
        cache.put("user-1", CachedStatus(true, "anna", null, now))
        cache.put("user-2", CachedStatus(true, "bruno", null, now))

        cache.clear()

        assertNull(cache.get("user-1"))
        assertNull(cache.get("user-2"))
        assertNull(backing.get(AttributionStatusCache.KEY))
    }

    @Test
    fun corruptJsonReadsAsEmptyAndSelfHeals() {
        backing.put(AttributionStatusCache.KEY, "not json at all")

        assertNull(cache.get("user-1"))

        cache.put("user-1", CachedStatus(true, "anna", null, now))
        assertEquals("anna", cache.get("user-1")?.influencerCode)
    }
}
