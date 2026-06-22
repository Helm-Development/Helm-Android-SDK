package dev.helmcode.helm.analytics

/**
 * Analytics-side accessor for the installation id. Delegates to the shared
 * [DeviceIdStore] so attribution and analytics resolve the same value (HELM-202).
 */
internal class InstallationStore(private val deviceIdStore: DeviceIdStore) {
    fun installationId(): String = deviceIdStore.deviceId()
}
