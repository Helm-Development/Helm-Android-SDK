package dev.helmcode.helm.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventQueueTest {

    private fun makeEvent(name: String = "tapped") = AnalyticsEvent(
        eventName = name,
        occurredAtMs = 1_700_000_000_000L,
        sessionId = "11111111-1111-1111-1111-111111111111",
        properties = mapOf("screen" to "home"),
    )

    @Test
    fun payloadShapeMatchesIngestContract() {
        val payload = makeEvent().payload()
        assertEquals("tapped", payload["event_name"])
        assertEquals("11111111-1111-1111-1111-111111111111", payload["session_id"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("home", (payload["properties"] as Map<String, Any?>)["screen"])
        // occurred_at must be ISO8601 UTC
        assertEquals("2023-11-14T22:13:20Z", payload["occurred_at"])
    }

    @Test
    fun enqueueSignalsFlushAtThreshold() {
        val queue = EventQueue()
        for (i in 1 until EventQueue.FLUSH_THRESHOLD) {
            assertFalse(queue.enqueue(makeEvent("e$i")))
        }
        assertTrue(queue.enqueue(makeEvent("last")))
    }

    @Test
    fun drainEmptiesQueue() {
        val queue = EventQueue()
        queue.enqueue(makeEvent())
        queue.enqueue(makeEvent())
        assertEquals(2, queue.drain().size)
        assertEquals(0, queue.count())
    }

    @Test
    fun capDropsOldest() {
        val queue = EventQueue()
        repeat(EventQueue.MAX_QUEUED + 10) { i -> queue.enqueue(makeEvent("e$i")) }
        val drained = queue.drain()
        assertEquals(EventQueue.MAX_QUEUED, drained.size)
        assertEquals("e10", drained.first().eventName)
    }

    @Test
    fun requeuePutsEventsBackInFront() {
        val queue = EventQueue()
        queue.enqueue(makeEvent("newer"))
        queue.requeue(listOf(makeEvent("failed")))
        assertEquals(listOf("failed", "newer"), queue.drain().map { it.eventName })
    }

    @Test
    fun retriedFlagDefaultsFalse() {
        assertFalse(makeEvent().retried)
    }
}
