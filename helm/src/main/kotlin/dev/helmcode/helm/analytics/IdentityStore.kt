package dev.helmcode.helm.analytics

/**
 * Persists the user_hash handed to the app by the TastySpread login response,
 * between identify() and clearIdentity().
 */
internal class IdentityStore(private val store: KeyValueStore) {

    private companion object {
        const val KEY = "helm_user_hash"
    }

    fun userHash(): String? = store.get(KEY)?.takeIf { it.isNotEmpty() }

    fun store(userHash: String) = store.put(KEY, userHash)

    fun clear() = store.remove(KEY)
}
