package dev.helmcode.helm.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SessionManagerTest {

    @Test
    fun newSessionOnInit() {
        val manager = SessionManager()
        assertNotNull(UUID.fromString(manager.sessionId))
        assertEquals(manager.sessionId, manager.sessionId.lowercase())
    }

    @Test
    fun shortBackgroundKeepsSession() {
        var fakeNow = 1_000_000_000L
        val manager = SessionManager(backgroundTimeoutMs = 300_000, now = { fakeNow })
        val original = manager.sessionId
        manager.appDidEnterBackground()
        fakeNow += 120_000 // 2 min < 5 min
        assertFalse(manager.appWillEnterForeground())
        assertEquals(original, manager.sessionId)
    }

    @Test
    fun longBackgroundRotatesSession() {
        var fakeNow = 1_000_000_000L
        val manager = SessionManager(backgroundTimeoutMs = 300_000, now = { fakeNow })
        val original = manager.sessionId
        manager.appDidEnterBackground()
        fakeNow += 300_001
        assertTrue(manager.appWillEnterForeground())
        assertNotEquals(original, manager.sessionId)
    }

    @Test
    fun foregroundWithoutBackgroundIsNoop() {
        val manager = SessionManager()
        val original = manager.sessionId
        assertFalse(manager.appWillEnterForeground())
        assertEquals(original, manager.sessionId)
    }
}
