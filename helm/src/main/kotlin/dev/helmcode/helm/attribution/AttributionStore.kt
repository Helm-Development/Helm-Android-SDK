package dev.helmcode.helm.attribution

import android.content.Context
import dev.helmcode.helm.analytics.DeviceIdStore
import dev.helmcode.helm.analytics.SharedPrefsStore

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
     * Returns the shared device id (HELM-202). Reads/creates via [DeviceIdStore],
     * seeding from this module's legacy `helm_device_id` for upgrade continuity.
     */
    fun getOrCreateDeviceId(context: Context): String {
        val canonical = SharedPrefsStore(context)
        return DeviceIdStore(canonical) { peekLegacyDeviceId(context) }.deviceId()
    }

    /** Legacy attribution device id, read-only (no creation). Null if never set. */
    fun peekLegacyDeviceId(context: Context): String? {
        val value = prefs(context).getString(KEY_DEVICE_ID, null)
        return if (value.isNullOrEmpty()) null else value
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
