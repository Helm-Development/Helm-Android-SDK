package dev.helmcode.helm.attribution

import android.util.Log
import dev.helmcode.helm.analytics.KeyValueStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Which attribution endpoint a queued submission replays against. */
internal enum class PendingKind(val wire: String) {
    PROMO_CODE("promo_code"),
    TRANSACTION("transaction");

    companion object {
        fun fromWire(wire: String?): PendingKind? = values().firstOrNull { it.wire == wire }
    }
}

/**
 * One attribution submission that failed for transport reasons and is waiting
 * to be replayed.
 *
 * @param enqueuedAtMs when the submission first failed. Preserved across
 *   re-enqueues so a repeatedly-retried submission cannot reset its own
 *   retention window.
 * @param debug the sandbox marker in force when the submission was *made*, not
 *   when it is replayed. A queued entry carries its own environment so a replay
 *   after a reconfigure still reports where the submission came from.
 */
internal data class PendingSubmission(
    val id: String,
    val kind: PendingKind,
    val userId: String,
    val enqueuedAtMs: Long,
    val code: String? = null,
    val originalTransactionId: String? = null,
    val debug: Boolean = false,
) {
    /**
     * Identity of the *logical* submission. Two entries with the same key are
     * the same request, so the queue holds at most one of them.
     *
     * [debug] participates: the same code submitted from a sandbox build and
     * from a live build are two distinct assertions to the server, and merging
     * them would silently discard one environment's marker.
     */
    fun dedupeKey(): String = "${kind.wire}|$userId|${code ?: originalTransactionId ?: ""}|$debug"

    fun toJson(): JSONObject = JSONObject().apply {
        put(FIELD_ID, id)
        put(FIELD_KIND, kind.wire)
        put(FIELD_USER_ID, userId)
        put(FIELD_ENQUEUED_AT, enqueuedAtMs)
        code?.let { put(FIELD_CODE, it) }
        originalTransactionId?.let { put(FIELD_TRANSACTION_ID, it) }
        put(FIELD_DEBUG, debug)
    }

    companion object {
        private const val FIELD_ID = "id"
        private const val FIELD_KIND = "kind"
        private const val FIELD_USER_ID = "user_id"
        private const val FIELD_ENQUEUED_AT = "enqueued_at_ms"
        private const val FIELD_CODE = "code"
        private const val FIELD_TRANSACTION_ID = "original_transaction_id"
        internal const val FIELD_DEBUG = "debug"

        fun promoCode(userId: String, code: String, nowMs: Long, debug: Boolean = false) = PendingSubmission(
            id = UUID.randomUUID().toString(),
            kind = PendingKind.PROMO_CODE,
            userId = userId,
            enqueuedAtMs = nowMs,
            code = code,
            debug = debug,
        )

        fun transaction(
            userId: String,
            originalTransactionId: String,
            nowMs: Long,
            debug: Boolean = false,
        ) = PendingSubmission(
            id = UUID.randomUUID().toString(),
            kind = PendingKind.TRANSACTION,
            userId = userId,
            enqueuedAtMs = nowMs,
            originalTransactionId = originalTransactionId,
            debug = debug,
        )

        /** Null for anything unreadable -- a corrupt row is dropped, not crashed on. */
        fun fromJson(json: JSONObject): PendingSubmission? {
            val kind = PendingKind.fromWire(json.optString(FIELD_KIND)) ?: return null
            val userId = json.optString(FIELD_USER_ID).takeIf { it.isNotEmpty() } ?: return null
            val id = json.optString(FIELD_ID).takeIf { it.isNotEmpty() } ?: return null
            val enqueuedAt = json.optLong(FIELD_ENQUEUED_AT, 0L).takeIf { it > 0L } ?: return null
            val code = json.optString(FIELD_CODE).takeIf { it.isNotEmpty() }
            val transactionId = json.optString(FIELD_TRANSACTION_ID).takeIf { it.isNotEmpty() }
            return PendingSubmission(
                id = id,
                kind = kind,
                userId = userId,
                enqueuedAtMs = enqueuedAt,
                code = code,
                originalTransactionId = transactionId,
                // Absent on rows written by 0.5.0 and earlier: those predate the
                // flag, so they are live. Never a reason to drop the row.
                debug = json.optBoolean(FIELD_DEBUG, false),
            )
        }
    }
}

/**
 * Durable FIFO queue of attribution submissions that failed for transport
 * reasons (no network, 5xx, 429, unparseable 2xx body).
 *
 * Persisted as a single JSON array through the [KeyValueStore] seam -- the same
 * backing prefs file as `DeviceIdStore`/`IdentityStore` -- so it round-trips in
 * unit tests without a Context.
 *
 * Terminal outcomes (4xx other than 429) are never queued: the server answered
 * and replaying would produce the same answer.
 *
 * Thread-safety: every method serializes on [lock]. Corrupt stored JSON is
 * logged and treated as empty; the next write self-heals it.
 */
internal class PendingSubmissionStore(
    private val store: KeyValueStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    companion object {
        private const val TAG = "HelmSDK"
        internal const val KEY = "helm_pending_attribution"

        /** Bounds the prefs blob; oldest entries are shed first (EventQueue precedent). */
        internal const val MAX_PENDING = 100

        /** Submissions older than this are dropped unsent. */
        internal const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }

    private val lock = Any()

    /**
     * Everything still eligible for replay, oldest first. Expired entries are
     * dropped and the pruned queue persisted, so retention is enforced on read
     * and never depends on a background sweep.
     */
    fun all(): List<PendingSubmission> = synchronized(lock) {
        val stored = read()
        val cutoff = clock() - RETENTION_MS
        val live = stored.filter { it.enqueuedAtMs > cutoff }
        if (live.size != stored.size) {
            Log.w(TAG, "dropped ${stored.size - live.size} attribution submission(s) past 30-day retention")
            write(live)
        }
        live.sortedBy { it.enqueuedAtMs }
    }

    /**
     * Persists [submission], replacing any entry for the same logical request.
     * The surviving entry keeps the *earlier* [PendingSubmission.enqueuedAtMs]
     * so repeated failures cannot extend the retention window.
     */
    fun enqueue(submission: PendingSubmission) = synchronized(lock) {
        val entries = read().toMutableList()
        val existingIndex = entries.indexOfFirst { it.dedupeKey() == submission.dedupeKey() }
        if (existingIndex >= 0) {
            val existing = entries[existingIndex]
            entries[existingIndex] = submission.copy(
                id = existing.id,
                enqueuedAtMs = minOf(existing.enqueuedAtMs, submission.enqueuedAtMs),
            )
        } else {
            entries.add(submission)
        }
        val capped = if (entries.size > MAX_PENDING) {
            Log.w(TAG, "attribution queue at cap -- shedding ${entries.size - MAX_PENDING} oldest")
            entries.sortedBy { it.enqueuedAtMs }.takeLast(MAX_PENDING)
        } else {
            entries
        }
        write(capped)
    }

    fun remove(id: String) = synchronized(lock) {
        val entries = read()
        val remaining = entries.filterNot { it.id == id }
        if (remaining.size != entries.size) write(remaining)
    }

    fun clear() = synchronized(lock) { store.remove(KEY) }

    fun count(): Int = synchronized(lock) { read().size }

    // internals -------------------------------------------------------------

    private fun read(): List<PendingSubmission> {
        val raw = store.get(KEY)?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { PendingSubmission.fromJson(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "corrupt attribution queue -- treating as empty: ${e.message}")
            emptyList()
        }
    }

    private fun write(entries: List<PendingSubmission>) {
        if (entries.isEmpty()) {
            store.remove(KEY)
            return
        }
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        store.put(KEY, array.toString())
    }
}
