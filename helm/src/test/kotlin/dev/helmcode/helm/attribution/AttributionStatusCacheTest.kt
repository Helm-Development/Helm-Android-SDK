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

    // ---- debug / sandbox marker (TAS-801) -------------------------------

    @Test
    fun debugMarkerRoundTrips() {
        cache.put("user-1", CachedStatus(true, "anna", "off_1", now, debug = true))
        cache.put("user-2", CachedStatus(true, "bruno", null, now))

        assertTrue(cache.get("user-1")!!.debug)
        assertFalse("the default is live", cache.get("user-2")!!.debug)
    }

    /**
     * Entries written by 0.5.0 have no `debug` key and describe live data. They
     * must read back as live rather than as a missing entry -- an upgrade must
     * not throw away a working offline fallback.
     */
    @Test
    fun legacyEntriesWithoutTheDebugFieldReadAsLive() {
        backing.put(
            AttributionStatusCache.KEY,
            """{"user-1":{"linked":true,"influencer_code":"anna","fetched_at_ms":$now}}""",
        )

        val cached = cache.get("user-1")!!
        assertTrue(cached.linked)
        assertEquals("anna", cached.influencerCode)
        assertFalse(cached.debug)
    }

    /**
     * The cache itself is environment-*labelled*, not environment-*keyed*: it
     * stores whatever it is given and the reader
     * ([AttributionApi.fetchAttributionStatus]) decides a mismatched entry is a
     * miss. Keeping the policy in one place is why the key format never had to
     * change.
     */
    @Test
    fun aLaterFetchInTheOtherEnvironmentOverwritesTheMarker() {
        cache.put("user-1", CachedStatus(true, "anna", "off_1", now, debug = true))
        cache.put("user-1", CachedStatus(true, "anna", "off_1", now + 1000, debug = false))

        assertFalse(cache.get("user-1")!!.debug)
    }

    @Test
    fun corruptJsonReadsAsEmptyAndSelfHeals() {
        backing.put(AttributionStatusCache.KEY, "not json at all")

        assertNull(cache.get("user-1"))

        cache.put("user-1", CachedStatus(true, "anna", null, now))
        assertEquals("anna", cache.get("user-1")?.influencerCode)
    }
}
