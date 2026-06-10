package dev.helmcode.helm.analytics

import java.util.UUID

/**
 * Persists the analytics installation id (UUID4, lowercase), generated once per install.
 *
 * Thread-safety: [installationId] is @Synchronized so two threads racing on first launch
 * cannot generate two different UUIDs.
 */
internal class InstallationStore(private val store: KeyValueStore) {

    private companion object {
        const val KEY = "helm_installation_id"
    }

    @Synchronized
    fun installationId(): String {
        store.get(KEY)?.takeIf { it.isNotEmpty() }?.let { return it }
        val newId = UUID.randomUUID().toString().lowercase()
        store.put(KEY, newId)
        return newId
    }
}
