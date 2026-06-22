package dev.helmcode.helm.analytics

import java.util.UUID

/**
 * Single source of truth for this install's device identifier, shared by the
 * analytics and attribution modules. Reuses the analytics `helm_installation_id`
 * key + prefs file as the canonical store so existing installs keep their id.
 *
 * Resolution order (HELM-202):
 *   1. existing canonical installation id  -> adopt verbatim (no orphaning)
 *   2. legacy attribution device id        -> adopt (upgrade continuity)
 *   3. mint a new lowercase UUIDv4
 *
 * Thread-safety: [deviceId] is @Synchronized so first-launch races cannot mint two ids.
 * Use a single shared instance per process: [deviceId]'s lock is instance-scoped,
 * so concurrent first-reads are only race-free through one shared instance.
 */
internal class DeviceIdStore(
    private val store: KeyValueStore,
    private val legacyAttributionDeviceId: () -> String? = { null },
) {
    private companion object {
        const val KEY = "helm_installation_id"
    }

    @Synchronized
    fun deviceId(): String {
        store.get(KEY)?.takeIf { it.isNotEmpty() }?.let { return it }
        legacyAttributionDeviceId()?.takeIf { it.isNotEmpty() }?.let { legacy ->
            store.put(KEY, legacy)
            return legacy
        }
        val minted = UUID.randomUUID().toString().lowercase()
        store.put(KEY, minted)
        return minted
    }
}
