# HELM-202 Device Identity Unification — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make attribution and analytics share one device identifier, and deliver the captured `attribution_id` to the backend as `attribution_token` on installation registration so the `Attribution → Installation` join closes.

**Architecture:** A single internal `DeviceIdStore` (analytics package, reuses the existing `helm_installation_id` key + `dev.helmcode.helm.analytics` prefs file as canonical, so already-registered installs keep their id). Both `InstallationStore` and attribution delegate to it. `Analytics.register()` includes `attribution_token` (always, when stored) and is re-fired when attribution matches, via an internal `onAttributionMatched` hook — robust to launch ordering.

**Tech Stack:** Kotlin, Android SDK (`:helm` library module), JUnit4 unit tests over the `KeyValueStore` seam.

**Spec:** `docs/superpowers/specs/2026-06-22-helm-202-device-identity-unification-design.md`

**Test command (whole module):** `./gradlew :helm:testDebugUnitTest`
**Single class:** `./gradlew :helm:testDebugUnitTest --tests "dev.helmcode.helm.analytics.DeviceIdStoreTest"`

---

## File Structure

- **Create** `helm/src/main/kotlin/dev/helmcode/helm/analytics/DeviceIdStore.kt` — canonical shared device id + 3-branch resolution.
- **Create** `helm/src/test/kotlin/dev/helmcode/helm/analytics/DeviceIdStoreTest.kt` — resolution branches, idempotency, thread-safety.
- **Modify** `helm/src/main/kotlin/dev/helmcode/helm/analytics/InstallationStore.kt` — delegate to `DeviceIdStore`.
- **Modify** `helm/src/test/kotlin/dev/helmcode/helm/analytics/StoresTest.kt` + `AnalyticsTest.kt` — construct `InstallationStore(DeviceIdStore(...))`.
- **Modify** `helm/src/main/kotlin/dev/helmcode/helm/analytics/AnalyticsClient.kt` — `attribution_token` in registration body.
- **Modify** `helm/src/test/kotlin/dev/helmcode/helm/analytics/AnalyticsClientTest.kt` — token include/omit.
- **Modify** `helm/src/main/kotlin/dev/helmcode/helm/analytics/Analytics.kt` — hold token, send on register, `onAttributionMatched` hook.
- **Modify** `helm/src/test/kotlin/dev/helmcode/helm/analytics/AnalyticsTest.kt` — hook test (same file as construction update).
- **Modify** `helm/src/main/kotlin/dev/helmcode/helm/attribution/AttributionStore.kt` — `peekLegacyDeviceId`; `getOrCreateDeviceId` delegates to shared store.
- **Modify** `helm/src/main/kotlin/dev/helmcode/helm/attribution/Attribution.kt` — fire hook on match.

---

## Task 1: `DeviceIdStore` — canonical shared device id

**Files:**
- Create: `helm/src/main/kotlin/dev/helmcode/helm/analytics/DeviceIdStore.kt`
- Test: `helm/src/test/kotlin/dev/helmcode/helm/analytics/DeviceIdStoreTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.helmcode.helm.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.UUID

class DeviceIdStoreTest {

    @Test
    fun adoptsExistingInstallationIdVerbatim() {
        val backing = InMemoryStore().apply { put("helm_installation_id", "existing-id-123") }
        // legacy provider must be ignored when an installation id already exists
        val id = DeviceIdStore(backing) { "legacy-attr-id" }.deviceId()
        assertEquals("existing-id-123", id)
    }

    @Test
    fun seedsFromLegacyAttributionIdWhenNoInstallationId() {
        val backing = InMemoryStore()
        val id = DeviceIdStore(backing) { "legacy-attr-id" }.deviceId()
        assertEquals("legacy-attr-id", id)
        // persisted under the canonical key
        assertEquals("legacy-attr-id", backing.get("helm_installation_id"))
    }

    @Test
    fun mintsLowercaseUuidWhenNothingPresent() {
        val backing = InMemoryStore()
        val id = DeviceIdStore(backing).deviceId()
        assertNotNull(UUID.fromString(id))
        assertEquals(id, id.lowercase())
    }

    @Test
    fun isIdempotentAcrossReads() {
        val backing = InMemoryStore()
        val store = DeviceIdStore(backing)
        assertEquals(store.deviceId(), store.deviceId())
    }

    @Test
    fun persistsAcrossInstancesSharingBacking() {
        val backing = InMemoryStore()
        assertEquals(DeviceIdStore(backing).deviceId(), DeviceIdStore(backing).deviceId())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :helm:testDebugUnitTest --tests "dev.helmcode.helm.analytics.DeviceIdStoreTest"`
Expected: FAIL — `DeviceIdStore` is unresolved (compilation error).

- [ ] **Step 3: Write minimal implementation**

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :helm:testDebugUnitTest --tests "dev.helmcode.helm.analytics.DeviceIdStoreTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add helm/src/main/kotlin/dev/helmcode/helm/analytics/DeviceIdStore.kt \
        helm/src/test/kotlin/dev/helmcode/helm/analytics/DeviceIdStoreTest.kt
git commit -m "feat(HELM-202): add shared DeviceIdStore with 3-branch resolution"
```

---

## Task 2: `InstallationStore` delegates to `DeviceIdStore`

**Files:**
- Modify: `helm/src/main/kotlin/dev/helmcode/helm/analytics/InstallationStore.kt`
- Modify (tests): `helm/src/test/kotlin/dev/helmcode/helm/analytics/StoresTest.kt`, `helm/src/test/kotlin/dev/helmcode/helm/analytics/AnalyticsTest.kt`

- [ ] **Step 1: Update the existing tests to the new constructor (these are the failing tests)**

In `StoresTest.kt`, replace the two `InstallationStore(...)` tests with:

```kotlin
    @Test
    fun installationIdIsLowercaseUuidGeneratedOnce() {
        val store = InstallationStore(DeviceIdStore(InMemoryStore()))
        val id = store.installationId()
        assertNotNull(UUID.fromString(id))
        assertEquals(id, id.lowercase())
        assertEquals(id, store.installationId())
    }

    @Test
    fun installationIdPersistsAcrossInstances() {
        val backing = InMemoryStore()
        assertEquals(InstallationStore(DeviceIdStore(backing)).installationId(),
                     InstallationStore(DeviceIdStore(backing)).installationId())
    }
```

In `AnalyticsTest.kt`, change `makeAnalytics()`'s installation store line from
`installationStore = InstallationStore(backing),` to:

```kotlin
        installationStore = InstallationStore(DeviceIdStore(backing)),
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :helm:testDebugUnitTest --tests "dev.helmcode.helm.analytics.StoresTest"`
Expected: FAIL — `InstallationStore(DeviceIdStore(...))` does not compile (constructor still takes `KeyValueStore`).

- [ ] **Step 3: Change `InstallationStore` to delegate**

Replace the whole body of `InstallationStore.kt` with:

```kotlin
package dev.helmcode.helm.analytics

/**
 * Analytics-side accessor for the installation id. Delegates to the shared
 * [DeviceIdStore] so attribution and analytics resolve the same value (HELM-202).
 */
internal class InstallationStore(private val deviceIdStore: DeviceIdStore) {
    fun installationId(): String = deviceIdStore.deviceId()
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :helm:testDebugUnitTest --tests "dev.helmcode.helm.analytics.StoresTest" --tests "dev.helmcode.helm.analytics.AnalyticsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add helm/src/main/kotlin/dev/helmcode/helm/analytics/InstallationStore.kt \
        helm/src/test/kotlin/dev/helmcode/helm/analytics/StoresTest.kt \
        helm/src/test/kotlin/dev/helmcode/helm/analytics/AnalyticsTest.kt
git commit -m "refactor(HELM-202): InstallationStore delegates to DeviceIdStore"
```

---

## Task 3: `AnalyticsClient.registrationBody` carries `attribution_token`

**Files:**
- Modify: `helm/src/main/kotlin/dev/helmcode/helm/analytics/AnalyticsClient.kt`
- Test: `helm/src/test/kotlin/dev/helmcode/helm/analytics/AnalyticsClientTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `AnalyticsClientTest.kt`:

```kotlin
    @Test
    fun registrationBodyIncludesAttributionTokenWhenPresent() {
        val body = AnalyticsClient.registrationBody(
            installationId = "iid",
            userHash = "",
            device = device,
            attributionToken = "attr-abc",
        )
        assertEquals("attr-abc", body["attribution_token"])
    }

    @Test
    fun registrationBodyOmitsAttributionTokenWhenNullOrEmpty() {
        val nullToken = AnalyticsClient.registrationBody("iid", "", device, null)
        val emptyToken = AnalyticsClient.registrationBody("iid", "", device, "")
        assertFalse(nullToken.containsKey("attribution_token"))
        assertFalse(emptyToken.containsKey("attribution_token"))
    }
```

(`assertFalse` is already imported in this file.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :helm:testDebugUnitTest --tests "dev.helmcode.helm.analytics.AnalyticsClientTest"`
Expected: FAIL — `registrationBody` has no `attributionToken` parameter (compilation error).

- [ ] **Step 3: Add the parameter (default null keeps existing callers compiling)**

In `AnalyticsClient.kt`, replace `registrationBody` and `registerInstallation` with:

```kotlin
    fun registrationBody(
        installationId: String,
        userHash: String,
        device: DeviceFacts,
        attributionToken: String? = null,
    ): Map<String, Any?> = buildMap {
        put("installation_id", installationId)
        put("platform", device.platform)
        put("app_version", device.appVersion)
        put("os_version", device.osVersion)
        put("locale", device.locale)
        put("timezone", device.timezone)
        put("user_hash", userHash)
        attributionToken?.takeIf { it.isNotEmpty() }?.let { put("attribution_token", it) }
    }
```

```kotlin
    suspend fun registerInstallation(
        installationId: String,
        userHash: String,
        device: DeviceFacts,
        attributionToken: String? = null,
    ) {
        HelmHttpClient.post(
            path = "/api/v1/analytics/installations/",
            body = registrationBody(installationId, userHash, device, attributionToken),
        )
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :helm:testDebugUnitTest --tests "dev.helmcode.helm.analytics.AnalyticsClientTest"`
Expected: PASS (existing `registrationBodyIncludesRequiredFieldsAndHash` still passes — required keys unchanged).

- [ ] **Step 5: Commit**

```bash
git add helm/src/main/kotlin/dev/helmcode/helm/analytics/AnalyticsClient.kt \
        helm/src/test/kotlin/dev/helmcode/helm/analytics/AnalyticsClientTest.kt
git commit -m "feat(HELM-202): registration body carries attribution_token when present"
```

---

## Task 4: `Analytics` holds the token, sends it on register, exposes `onAttributionMatched`

**Files:**
- Modify: `helm/src/main/kotlin/dev/helmcode/helm/analytics/Analytics.kt`
- Test: `helm/src/test/kotlin/dev/helmcode/helm/analytics/AnalyticsTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `AnalyticsTest.kt`:

```kotlin
    @Test
    fun onAttributionMatchedStoresTokenForRegistration() {
        val analytics = makeAnalytics()
        analytics.startForTest()
        analytics.onAttributionMatched("attr-xyz")
        assertEquals("attr-xyz", analytics.attributionTokenForTest())
    }

    @Test
    fun onAttributionMatchedIgnoresEmptyToken() {
        val analytics = makeAnalytics()
        analytics.startForTest()
        analytics.onAttributionMatched("")
        assertNull(analytics.attributionTokenForTest())
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :helm:testDebugUnitTest --tests "dev.helmcode.helm.analytics.AnalyticsTest"`
Expected: FAIL — `onAttributionMatched` / `attributionTokenForTest` unresolved.

- [ ] **Step 3: Implement in `Analytics.kt`**

a) Add the import near the other imports:

```kotlin
import dev.helmcode.helm.attribution.AttributionStore
```

b) Add the field next to the other `@Volatile`/private state (after `private var deviceFacts`):

```kotlin
    @Volatile private var attributionToken: String? = null
```

c) Inside `start(context)`, within the `synchronized(stateLock)` block (after `if (deviceFacts == null) ...`), capture the persisted token so it is set before `register()` runs:

```kotlin
            attributionToken = AttributionStore.getAttributionId(context)
```

d) Change `register()` to pass the token. Replace its body's `scope.launch { ... }` call with the 4-arg form:

```kotlin
        scope.launch {
            try {
                AnalyticsClient.registerInstallation(installationId, userHash, device, attributionToken)
            } catch (e: Exception) {
                Log.e(TAG, "registration failed: ${e.message}")
            }
        }
```

e) Add the public-internal hook and a test accessor after `clearIdentity()`:

```kotlin
    /**
     * Called by the attribution module when an install is matched to a tracking link.
     * Stores the attribution_token and (if started) re-registers so the backend
     * closes the Attribution -> Installation join. Robust to launch ordering.
     */
    internal fun onAttributionMatched(token: String?) {
        attributionToken = token?.takeIf { it.isNotEmpty() }
        if (started) register()
    }

    internal fun attributionTokenForTest(): String? = attributionToken
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :helm:testDebugUnitTest --tests "dev.helmcode.helm.analytics.AnalyticsTest"`
Expected: PASS (all AnalyticsTest cases, including the two new ones).

- [ ] **Step 5: Commit**

```bash
git add helm/src/main/kotlin/dev/helmcode/helm/analytics/Analytics.kt \
        helm/src/test/kotlin/dev/helmcode/helm/analytics/AnalyticsTest.kt
git commit -m "feat(HELM-202): Analytics sends attribution_token and re-registers on match"
```

---

## Task 5: Attribution reads the shared id and fires the hook on match

**Files:**
- Modify: `helm/src/main/kotlin/dev/helmcode/helm/attribution/AttributionStore.kt`
- Modify: `helm/src/main/kotlin/dev/helmcode/helm/attribution/Attribution.kt`

> Note: `AttributionStore` is a Context-bound `object`; per the file's own header it is exercised by Robolectric/instrumented tests, not JVM unit tests. No new JVM unit test here — correctness is covered by the `DeviceIdStore` tests (Task 1) and verified by compilation + the full suite (Task 6). Keep these methods thin.

- [ ] **Step 1: Add legacy peek + delegate `getOrCreateDeviceId` in `AttributionStore.kt`**

Add the imports:

```kotlin
import dev.helmcode.helm.analytics.DeviceIdStore
import dev.helmcode.helm.analytics.SharedPrefsStore
```

Replace `getOrCreateDeviceId` with the delegating version and add `peekLegacyDeviceId`:

```kotlin
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
```

(`KEY_DEVICE_ID`, `prefs`, `getAttributionId`, `storeMatch`, `storeUnmatched` are unchanged.)

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :helm:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (`SharedPrefsStore`/`DeviceIdStore` are `internal` but same `:helm` module, so cross-package access compiles.)

- [ ] **Step 3: Fire the re-register hook on a successful match in `Attribution.kt`**

Add the import:

```kotlin
import dev.helmcode.helm.analytics.Analytics
```

In `performMatch`, the referrer branch currently does:

```kotlin
            if (attributionId != null) {
                AttributionStore.storeMatch(context, attributionId)
            } else {
                AttributionStore.storeUnmatched(context)
            }
            return
```

Replace with:

```kotlin
            if (attributionId != null) {
                AttributionStore.storeMatch(context, attributionId)
                Analytics.instance.onAttributionMatched(attributionId)
            } else {
                AttributionStore.storeUnmatched(context)
            }
            return
```

And the fingerprint branch at the end currently does:

```kotlin
        if (!attributionId.isNullOrEmpty()) {
            AttributionStore.storeMatch(context, attributionId)
        } else {
            AttributionStore.storeUnmatched(context)
        }
```

Replace with:

```kotlin
        if (!attributionId.isNullOrEmpty()) {
            AttributionStore.storeMatch(context, attributionId)
            Analytics.instance.onAttributionMatched(attributionId)
        } else {
            AttributionStore.storeUnmatched(context)
        }
```

(`storeUnmatched` paths intentionally do NOT call the hook — no token to bind.)

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :helm:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add helm/src/main/kotlin/dev/helmcode/helm/attribution/AttributionStore.kt \
        helm/src/main/kotlin/dev/helmcode/helm/attribution/Attribution.kt
git commit -m "feat(HELM-202): attribution uses shared device id and notifies analytics on match"
```

---

## Task 6: Full-suite verification

**Files:** none (verification only)

- [ ] **Step 1: Run the entire module unit-test suite**

Run: `./gradlew :helm:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — all tests pass (DeviceIdStoreTest, StoresTest, AnalyticsTest, AnalyticsClientTest, EventQueueTest, SessionManagerTest, AttributionStoreTest, HelmHttpClientTest, HelmAnalyticsInterceptorTest).

- [ ] **Step 2: Build the library to confirm no integration breakage**

Run: `./gradlew :helm:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Final commit (only if Steps 1–2 produced any incidental fixes)**

```bash
git status   # if clean, nothing to commit
```

---

## Notes for the implementer

- **Do not change the value of `helm_installation_id`** for an install that already has one — `DeviceIdStore` branch 1 guarantees this; it is the property that prevents server-side orphaning.
- **`attribution_token` is always sent when stored** (idempotent backend bind, self-healing) — this is intentional, not a bug.
- **Out of scope** (separate follow-up stories): the ephemeral logging module and endpoint-prefix reconciliation (`/attribution/*` vs `/api/v1/analytics/*`).
