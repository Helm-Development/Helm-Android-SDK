package dev.helmcode.helm.analytics

import android.content.Context

/**
 * Minimal persistence seam so stores are unit-testable without a Context.
 */
internal interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/**
 * SharedPreferences-backed production implementation.
 */
internal class SharedPrefsStore(context: Context) : KeyValueStore {
    private val prefs = context.applicationContext
        .getSharedPreferences("dev.helmcode.helm.analytics", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun remove(key: String) = prefs.edit().remove(key).apply()
}
