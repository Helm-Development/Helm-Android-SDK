package dev.helmcode.helm.attribution

import android.content.Context
import java.util.UUID

/**
 * Persistent storage for attribution state using SharedPreferences.
 */
internal object AttributionStore {

    private const val PREFS_NAME = "helm_sdk_prefs"
    private const val KEY_CHECKED = "helm_attribution_checked"
    private const val KEY_ATTRIBUTION_ID = "helm_attribution_id"
    private const val KEY_DEVICE_ID = "helm_device_id"

    /**
     * Returns true if attribution has already been checked (matched or unmatched).
     */
    fun hasChecked(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_CHECKED, false)
    }

    /**
     * Returns the attribution ID, or null if empty or not set.
     */
    fun getAttributionId(context: Context): String? {
        val value = prefs(context).getString(KEY_ATTRIBUTION_ID, null)
        return if (value.isNullOrEmpty()) null else value
    }

    /**
     * Returns the raw stored attribution ID string as-is.
     */
    fun getRawAttributionId(context: Context): String {
        return prefs(context).getString(KEY_ATTRIBUTION_ID, "") ?: ""
    }

    /**
     * Store a successful attribution match.
     */
    fun storeMatch(context: Context, attributionId: String) {
        prefs(context).edit()
            .putString(KEY_ATTRIBUTION_ID, attributionId)
            .putBoolean(KEY_CHECKED, true)
            .apply()
    }

    /**
     * Store that attribution was checked but no match was found.
     */
    fun storeUnmatched(context: Context) {
        prefs(context).edit()
            .putString(KEY_ATTRIBUTION_ID, "")
            .putBoolean(KEY_CHECKED, true)
            .apply()
    }

    /**
     * Get or create a persistent device ID (UUID).
     * Generated once on first call, then persisted in SharedPreferences.
     */
    fun getOrCreateDeviceId(context: Context): String {
        val existing = prefs(context).getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrEmpty()) {
            return existing
        }

        val deviceId = UUID.randomUUID().toString()
        prefs(context).edit()
            .putString(KEY_DEVICE_ID, deviceId)
            .apply()
        return deviceId
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
