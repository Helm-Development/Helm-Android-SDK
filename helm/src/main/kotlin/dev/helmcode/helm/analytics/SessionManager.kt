package dev.helmcode.helm.analytics

import java.util.UUID

/**
 * Owns the analytics session id: new UUID on cold start, rotated when the app
 * returns to the foreground after more than [backgroundTimeoutMs]. Clock is
 * injected for tests. Memory-only by design (spec §4).
 *
 * Thread-safety: all mutable state is guarded by [lock] so sessionId reads from
 * any thread and rotations from lifecycle callbacks are race-free.
 */
internal class SessionManager(
    private val backgroundTimeoutMs: Long = 300_000,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()
    private var _sessionId: String = newId()
    private var backgroundedAt: Long? = null

    val sessionId: String
        get() = synchronized(lock) { _sessionId }

    fun appDidEnterBackground() {
        synchronized(lock) { backgroundedAt = now() }
    }

    /**
     * Rotates the session if the background stay exceeded the timeout.
     * @return true when a new session started.
     */
    fun appWillEnterForeground(): Boolean = synchronized(lock) {
        val backgrounded = backgroundedAt
        backgroundedAt = null
        if (backgrounded == null || now() - backgrounded <= backgroundTimeoutMs) return false
        _sessionId = newId()
        return true
    }

    private fun newId() = UUID.randomUUID().toString().lowercase()
}
