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
 * Thread-safety: [deviceId] serializes on a process-wide [LOCK], so even separate
 * instances over the same backing prefs (analytics + attribution both resolve at
 * launch on background threads) cannot mint divergent ids on first read.
 */
internal class DeviceIdStore(
    private val store: KeyValueStore,
    private val legacyAttributionDeviceId: () -> String? = { null },
) {
    private companion object {
        const val KEY = "helm_installation_id"
        val LOCK = Any()
    }

    fun deviceId(): String = synchronized(LOCK) {
        store.get(KEY)?.takeIf { it.isNotEmpty() }?.let { return@synchronized it }
        legacyAttributionDeviceId()?.takeIf { it.isNotEmpty() }?.let { legacy ->
            store.put(KEY, legacy)
            return@synchronized legacy
        }
        val minted = UUID.randomUUID().toString().lowercase()
        store.put(KEY, minted)
        minted
    }
}
