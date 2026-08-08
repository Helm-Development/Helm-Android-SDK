package dev.helmcode.helm.attribution

import android.util.Log
import dev.helmcode.helm.analytics.KeyValueStore
import org.json.JSONObject

/** A previously-fetched attribution status, kept so a status read survives an outage. */
internal data class CachedStatus(
    val linked: Boolean,
    val influencerCode: String?,
    val offeringId: String?,
    val fetchedAtMs: Long,
)

/**
 * Last-known attribution status, keyed by the opaque user id.
 *
 * Per-user rather than per-device: several identities can share one install
 * (account switching, shared devices), and one user's link state must never be
 * served for another. Persisted as a single JSON object through the
 * [KeyValueStore] seam so it is unit-testable without a Context.
 *
 * Refreshed on a successful status fetch, a successful promo-code submit, and a
 * successful promo-code replay -- each of which is a complete, authoritative
 * status. Wiped by `Attribution.reset()` and by `Analytics.clearIdentity()`.
 *
 * Thread-safety: every method serializes on [lock]. Corrupt JSON is logged and
 * treated as empty.
 */
internal class AttributionStatusCache(
    private val store: KeyValueStore,
) {

    companion object {
        private const val TAG = "HelmSDK"
        internal const val KEY = "helm_attribution_status_cache"

        private const val FIELD_LINKED = "linked"
        private const val FIELD_INFLUENCER_CODE = "influencer_code"
        private const val FIELD_OFFERING_ID = "offering_id"
        private const val FIELD_FETCHED_AT = "fetched_at_ms"
    }

    private val lock = Any()

    fun get(userId: String): CachedStatus? = synchronized(lock) {
        val entry = read().optJSONObject(userId) ?: return null
        CachedStatus(
            linked = entry.optBoolean(FIELD_LINKED, false),
            influencerCode = entry.optString(FIELD_INFLUENCER_CODE).takeIf { it.isNotEmpty() },
            offeringId = entry.optString(FIELD_OFFERING_ID).takeIf { it.isNotEmpty() },
            fetchedAtMs = entry.optLong(FIELD_FETCHED_AT, 0L),
        )
    }

    fun put(userId: String, status: CachedStatus) = synchronized(lock) {
        val root = read()
        val entry = JSONObject().apply {
            put(FIELD_LINKED, status.linked)
            status.influencerCode?.let { put(FIELD_INFLUENCER_CODE, it) }
            status.offeringId?.let { put(FIELD_OFFERING_ID, it) }
            put(FIELD_FETCHED_AT, status.fetchedAtMs)
        }
        root.put(userId, entry)
        store.put(KEY, root.toString())
    }

    fun clear() = synchronized(lock) { store.remove(KEY) }

    private fun read(): JSONObject {
        val raw = store.get(KEY)?.takeIf { it.isNotEmpty() } ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            Log.w(TAG, "corrupt attribution status cache -- treating as empty: ${e.message}")
            JSONObject()
        }
    }
}
