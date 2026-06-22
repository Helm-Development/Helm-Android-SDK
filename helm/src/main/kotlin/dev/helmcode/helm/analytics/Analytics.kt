package dev.helmcode.helm.analytics

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.helmcode.helm.Configuration
import dev.helmcode.helm.networking.HelmError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Product analytics: installation registration, sessions, event batching,
 * and the identity headers for the app's own API traffic.
 *
 * Usage:
 * ```
 * Helm.configure(publishableKey = "pk_...", baseURL = "https://helmcode.dev")
 * Helm.analytics.start(context)
 * // after login:
 * Helm.analytics.identify(response.helmUserHash)
 * // on the app's OkHttpClient:
 * builder.addInterceptor(HelmAnalyticsInterceptor())
 * ```
 *
 * Thread-safety: all mutable state ([started], [installationStore], [identityStore],
 * [deviceFacts]) is guarded by [stateLock]. [started] is also @Volatile so reads
 * from track()/headers() don't pay lock overhead after the first start. The
 * check-and-set in start() ensures only one flush loop ever starts.
 * [observeLifecycle] and network calls are invoked OUTSIDE [stateLock] to avoid
 * holding the lock during potentially blocking operations.
 */
class Analytics internal constructor(
    private var installationStore: InstallationStore?,
    private var identityStore: IdentityStore?,
    private val sessionManager: SessionManager,
    private val queue: EventQueue,
    private val deviceFactsOverride: DeviceFacts? = null,
) {

    companion object {
        private const val TAG = "HelmAnalytics"
        private const val FLUSH_INTERVAL_MS = 30_000L

        val instance: Analytics by lazy {
            Analytics(
                installationStore = null, // bound in start(context)
                identityStore = null,
                sessionManager = SessionManager(),
                queue = EventQueue(),
            )
        }
    }

    // Single scope — one flush loop ever; guarded by stateLock check-and-set.
    // Process-lifetime SupervisorJob scope: intentionally never cancelled (singleton).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()

    @Volatile private var started = false
    private var deviceFacts: DeviceFacts? = deviceFactsOverride

    /**
     * Activates analytics: binds persistence, registers the installation with
     * Helm, and begins lifecycle tracking and timed flushes.
     * Call once after Helm.configure, e.g. in Application.onCreate (main thread).
     * Idempotent: second call under concurrent access returns without side-effects.
     */
    fun start(context: Context) {
        if (Configuration.instance == null) {
            Log.w(TAG, "start() before Helm.configure(...) — ignored")
            return
        }
        synchronized(stateLock) {
            if (started) return
            started = true
            val backing = SharedPrefsStore(context)
            if (installationStore == null) installationStore = InstallationStore(DeviceIdStore(backing))
            if (identityStore == null) identityStore = IdentityStore(backing)
            if (deviceFacts == null) deviceFacts = DeviceFacts.collect(context)
        }
        // observeLifecycle posts to the main thread itself; it and the network-bound
        // calls below run OUTSIDE stateLock to avoid holding the lock while blocking.
        observeLifecycle()
        startFlushLoop()
        register()
    }

    /** Bind the user identity from the login response's helm_user_hash. */
    fun identify(userHash: String) {
        val store = synchronized(stateLock) {
            identityStore ?: run {
                Log.w(TAG, "identify() before start() — ignored")
                return
            }
        }
        store.store(userHash)
        if (started) register() // re-registration binds the hash server-side
    }

    /**
     * Drop the identity on logout. The installation stays bound server-side
     * to its last-known user (spec §5).
     */
    fun clearIdentity() {
        val store = synchronized(stateLock) { identityStore ?: return }
        store.clear()
    }

    /** Queue a custom event. Flushes automatically at 50 events / 30 s / background. */
    fun track(name: String, properties: Map<String, Any?> = emptyMap()) {
        if (!started) {
            Log.w(TAG, "track(\"$name\") before start() — dropped")
            return
        }
        val event = AnalyticsEvent(
            eventName = name,
            occurredAtMs = System.currentTimeMillis(),
            sessionId = sessionManager.sessionId,
            properties = properties,
        )
        if (queue.enqueue(event)) flush()
    }

    /** Identity headers for the app's own backend traffic. */
    fun headers(): Map<String, String> {
        val (installStore, idStore, facts) = synchronized(stateLock) {
            Triple(installationStore, identityStore, deviceFacts)
        }
        val installationId = installStore?.installationId() ?: return emptyMap()
        val headers = mutableMapOf(
            "X-Helm-Installation-Id" to installationId,
            "X-Helm-Session-Id" to sessionManager.sessionId,
            "X-Helm-App-Version" to (facts?.appVersion ?: "unknown"),
            "X-Helm-Platform" to (facts?.platform ?: "android"),
        )
        idStore?.userHash()?.let { headers["X-Helm-User-Hash"] = it }
        return headers
    }

    /** Sends queued events now. Called automatically; public for app hooks. */
    fun flush() {
        scope.launch { flushNow() }
    }

    // internals -------------------------------------------------------------

    internal fun queuedEventCount(): Int = queue.count()

    /** Test hook: mark started without Context-bound wiring (stores injected in constructor). */
    internal fun startForTest() {
        synchronized(stateLock) {
            if (started) return
            started = true
        }
        // Do NOT start the flush loop or observe lifecycle in tests.
    }

    private fun register() {
        val (installStore, idStore, facts) = synchronized(stateLock) {
            Triple(installationStore, identityStore, deviceFacts)
        }
        val installationId = installStore?.installationId() ?: return
        val userHash = idStore?.userHash() ?: ""
        val device = facts ?: return
        scope.launch {
            try {
                AnalyticsClient.registerInstallation(installationId, userHash, device)
            } catch (e: Exception) {
                // Registration retries on next launch; never surfaces (spec §6).
                Log.e(TAG, "registration failed: ${e.message}")
            }
        }
    }

    private suspend fun flushNow() {
        val batch = queue.drain()
        if (batch.isEmpty()) return
        val installationId = synchronized(stateLock) { installationStore }
            ?.installationId() ?: return
        try {
            AnalyticsClient.sendEvents(installationId, batch)
        } catch (e: HelmError.ServerError) {
            if (e.code == 429 || e.code >= 500) {
                requeueOnce(batch) // next loop tick (≥30 s) provides the backoff
            } else {
                Log.e(TAG, "events rejected (${e.code}): ${e.message} — dropped")
            }
        } catch (e: Exception) {
            requeueOnce(batch) // network error
        }
    }

    private fun requeueOnce(batch: List<AnalyticsEvent>) {
        val retryable = batch.filter { !it.retried }.map { it.copy(retried = true) }
        if (retryable.size < batch.size) {
            Log.w(TAG, "dropped ${batch.size - retryable.size} events after second failure")
        }
        val shed = queue.requeue(retryable)
        if (shed > 0) Log.w(TAG, "queue cap shed $shed events during requeue")
    }

    private fun startFlushLoop() {
        scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flushNow()
            }
        }
    }

    private fun observeLifecycle() {
        // LifecycleRegistry.addObserver enforces the main thread — hop defensively.
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    sessionManager.appDidEnterBackground()
                    flush() // flush-on-background durability (spec §2)
                }

                override fun onStart(owner: LifecycleOwner) {
                    sessionManager.appWillEnterForeground()
                }
            })
        }
    }
}
