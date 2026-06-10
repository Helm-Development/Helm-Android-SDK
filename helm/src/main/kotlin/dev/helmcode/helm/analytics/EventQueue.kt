package dev.helmcode.helm.analytics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// java.time.Instant needs API 26; minSdk is 24, so format with SimpleDateFormat.
// Not thread-safe — every use must synchronize on the formatter itself.
private val iso8601 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

/**
 * One analytics event captured locally before flush.
 *
 * @property retried true once this event has survived one failed flush; retried
 * events are dropped rather than re-queued a second time (spec §6).
 */
internal data class AnalyticsEvent(
    val eventName: String,
    val occurredAtMs: Long,
    val sessionId: String,
    val properties: Map<String, Any?> = emptyMap(),
    val retried: Boolean = false,
) {
    /** The JSON shape POST /api/v1/analytics/events/ expects per event. */
    fun payload(): Map<String, Any?> = mapOf(
        "event_name" to eventName,
        "occurred_at" to synchronized(iso8601) { iso8601.format(Date(occurredAtMs)) },
        "session_id" to sessionId,
        "properties" to properties,
    )
}

/**
 * Thread-safe in-memory event buffer. Flush-on-background durability (spec §2):
 * nothing is persisted to disk.
 */
internal class EventQueue {

    companion object {
        const val FLUSH_THRESHOLD = 50
        const val MAX_QUEUED = 500
    }

    private val events = mutableListOf<AnalyticsEvent>()
    private val lock = Any()

    /**
     * Appends an event. @return true when the queue has reached the flush
     * threshold and the caller should flush.
     */
    fun enqueue(event: AnalyticsEvent): Boolean = synchronized(lock) {
        events.add(event)
        while (events.size > MAX_QUEUED) events.removeAt(0)
        events.size >= FLUSH_THRESHOLD
    }

    /** Removes and returns everything queued. */
    fun drain(): List<AnalyticsEvent> = synchronized(lock) {
        val drained = events.toList()
        events.clear()
        drained
    }

    /** Puts failed events back at the front (preserving order), capped at MAX_QUEUED. */
    fun requeue(failed: List<AnalyticsEvent>) = synchronized(lock) {
        val combined = failed + events
        events.clear()
        events.addAll(if (combined.size <= MAX_QUEUED) combined else combined.takeLast(MAX_QUEUED))
    }

    fun count(): Int = synchronized(lock) { events.size }
}
