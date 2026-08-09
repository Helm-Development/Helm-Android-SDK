# HELM-221 — Android SDK: attribution methods (promo code, status, transaction) + offline queue and status cache

**Ticket:** [HELM-221](https://youtrack.codingbarber.com/issue/HELM-221) (In Progress) — child of epic HELM-218 ("SDK additions" + "Offline queue & status cache" sections are the authoritative design).
**Worktree:** `/Users/robertbarber/Projects/Helm/worktrees/HELM-221-android-sdk` — branch `task/HELM-221-attribution-methods` (already created off `main`; HEAD `5c5a007`).
**Latest release tag:** `v0.4.0` → this ships as **`v0.5.0`**.

---

## 1. Current-state facts the plan builds on (verified in worktree)

- `Helm` (`helm/src/main/kotlin/dev/helmcode/helm/Helm.kt`) is an `object` facade; `Helm.configure(publishableKey, baseURL)` takes **no Context**; `Helm.attribution` → `Attribution.instance` singleton.
- `Attribution` (`.../attribution/Attribution.kt`) holds path constants `PATH_REFERRER/PATH_MATCH/PATH_EVENT` in its `companion object` (internal `const val`), caches `@Volatile private var appContext` set in `match(context)`, and launches work with `CoroutineScope(Dispatchers.IO).launch { ... }` wrapped in try/catch + `Log.w`.
- `AttributionStore` (`.../attribution/AttributionStore.kt`) is the SharedPreferences precedent (`helm_sdk_prefs` file) and exposes `getOrCreateDeviceId(context)` → shared HELM-202 device id via `DeviceIdStore(SharedPrefsStore(context))`.
- `KeyValueStore` seam (`.../analytics/KeyValueStore.kt`): `internal interface KeyValueStore { get/put/remove }` + `SharedPrefsStore(context)` backed by prefs file `"dev.helmcode.helm.analytics"`. All persistence (`DeviceIdStore`, `IdentityStore`, `InstallationStore`) goes through this seam so stores unit-test with the `InMemoryStore` from `StoresTest.kt` — **the offline queue and status cache must follow this exact pattern**.
- `HelmHttpClient` (`.../networking/HelmHttpClient.kt`) is POST-only (`HttpURLConnection` + `org.json`), `withContext(Dispatchers.IO)`, throws internal `HelmError`: `NotConfigured`, `NetworkError(cause)`, `InvalidResponse`, `ServerError(code: Int, message: String /* raw error body */)`. Reads `Configuration.instance` for base URL + `Authorization: Bearer pk_...`.
- `Analytics` already depends on `androidx.lifecycle:lifecycle-process:2.7.0` and registers a `ProcessLifecycleOwner` observer via `Handler(Looper.getMainLooper()).post { ... }` (`observeLifecycle()` in `Analytics.kt`) — copy this exact registration idiom for the foreground replay trigger.
- `Analytics.clearIdentity()` is the identity-clear entry point (wipes `IdentityStore` only today).
- Tests: JUnit4, `mockito-kotlin`, MockWebServer 4.12.0 (`testImplementation`), `unitTests.isReturnDefaultValues = true`, **no Robolectric** — anything needing a real `Context`/`SharedPreferences` must be reachable through the `KeyValueStore` seam instead. `AttributionPathsTest` (`helm/src/test/kotlin/dev/helmcode/helm/attribution/AttributionPathsTest.kt`) asserts exact path strings.
- Publishing: `helm/build.gradle.kts` `afterEvaluate` publication, `version = project.findProperty("VERSION_NAME") ?: "0.2.0"`; `jitpack.yml` runs `./gradlew :helm:publishToMavenLocal` on openjdk17; JitPack rewrites the version to the requested tag.
- `docs/superpowers/` convention: plan in `docs/superpowers/plans/YYYY-MM-DD-<slug>.md`, design in `docs/superpowers/specs/` (checkbox tasks, test-first steps, test commands at top). This plan should be copied there (dated) when implementation starts.

---

## 2. Files — new and modified

### New files

| Path | Contents |
|---|---|
| `helm/src/main/kotlin/dev/helmcode/helm/HelmResult.kt` | **Public** `sealed class HelmResult<out T>` + result payload types `PromoCodeLink`, `AttributionStatus` (see §3). Root package so it reads as `dev.helmcode.helm.HelmResult` next to `Helm`. |
| `helm/src/main/kotlin/dev/helmcode/helm/attribution/PendingSubmissionStore.kt` | Internal offline queue persistence over `KeyValueStore` (see §6). |
| `helm/src/main/kotlin/dev/helmcode/helm/attribution/AttributionStatusCache.kt` | Internal per-userId status cache over `KeyValueStore` (see §7). |
| `helm/src/main/kotlin/dev/helmcode/helm/attribution/AttributionApi.kt` | Internal engine: pure body builders, `HelmError` → `HelmResult` classification, submit/fetch/replay logic. Constructor-injected `KeyValueStore`, `deviceId: () -> String`, `clock: () -> Long` so it is fully unit-testable without a Context (mirrors `Analytics`'s injected-store design). |
| `helm/src/test/kotlin/dev/helmcode/helm/attribution/PendingSubmissionStoreTest.kt` | Queue persistence, dedupe, retention tests (InMemoryStore + fixed clock). |
| `helm/src/test/kotlin/dev/helmcode/helm/attribution/AttributionStatusCacheTest.kt` | Cache round-trip, per-user isolation, wipe. |
| `helm/src/test/kotlin/dev/helmcode/helm/attribution/AttributionApiTest.kt` | MockWebServer end-to-end tests (see §10). |

### Modified files

| Path | Change |
|---|---|
| `helm/src/main/kotlin/dev/helmcode/helm/attribution/Attribution.kt` | + 3 path constants; + public suspend `submitPromoCode` / `fetchAttributionStatus` / `submitOriginalTransactionId`; + fire-and-forget overload; + public `reset()`; + internal `bind(context)` / replay wiring / foreground observer; internal `onIdentityCleared()`. |
| `helm/src/main/kotlin/dev/helmcode/helm/Helm.kt` | + `configure(context: Context, publishableKey: String, baseURL: String)` overload (non-breaking; keeps 2-arg). Binds `Attribution` context and triggers queue replay per the epic's "replay on configure" trigger. |
| `helm/src/main/kotlin/dev/helmcode/helm/analytics/Analytics.kt` | `clearIdentity()` additionally calls `Attribution.instance.onIdentityCleared()` (wipes queue + status cache). |
| `helm/src/test/kotlin/dev/helmcode/helm/attribution/AttributionPathsTest.kt` | + 3 assertions for the new paths (hard parity contract). |
| `helm/build.gradle.kts` | Publication fallback version `"0.2.0"` → `"0.5.0"`. |

No changes to `jitpack.yml`, `HelmHttpClient.kt`, `HelmError.kt`, `Configuration.kt`, `gradle.properties`.

---

## 3. Public API (exact)

```kotlin
// dev/helmcode/helm/HelmResult.kt
package dev.helmcode.helm

/** Result of a Helm attribution call. */
sealed class HelmResult<out T> {
    /** The call succeeded. */
    data class Success<T>(val value: T) : HelmResult<T>()

    /**
     * The backend rejected the call (terminal) or the SDK could not perform it.
     * @param code backend error envelope code (e.g. "invalid_code", "code_inactive",
     *   "already_linked", "invalid_token") or SDK-local codes "not_configured" /
     *   "network_error" (status fetch with no cache).
     * @param httpStatus HTTP status when a response was received, else null.
     */
    data class Failure(
        val code: String,
        val httpStatus: Int? = null,
        val message: String? = null,
    ) : HelmResult<Nothing>()

    /**
     * Transport failure: the submission was persisted and will be replayed
     * automatically (configure / app foreground / next attribution call) for up
     * to 30 days. Returned only by submitPromoCode / submitOriginalTransactionId.
     */
    object Queued : HelmResult<Nothing>()
}

/** Successful promo-code link: {linked: true, influencer_code, offering_id}. */
data class PromoCodeLink(
    val influencerCode: String,
    val offeringId: String?,
)

/** Customer's minimal attribution status: {linked, influencer_code?, offering_id?}. */
data class AttributionStatus(
    val isLinked: Boolean,
    val influencerCode: String?,
    val offeringId: String?,
    /** true when served from the local cache because the network was unavailable. */
    val fromCache: Boolean,
)
```

On `Attribution` (all `userId` params are **opaque Strings passed through verbatim** — no validation/normalization; KDoc on every method + on `Helm.configure` must state it must equal the app's RevenueCat app user ID, per HELM-218 identity model):

```kotlin
suspend fun submitPromoCode(userId: String, code: String): HelmResult<PromoCodeLink>
suspend fun fetchAttributionStatus(userId: String): HelmResult<AttributionStatus>
suspend fun submitOriginalTransactionId(userId: String, originalTransactionId: String): HelmResult<Unit>
fun submitOriginalTransactionId(userId: String, originalTransactionId: String)  // fire-and-forget + queued
fun reset()  // wipes pending queue + status cache (call on logout)
```

`internal sealed class HelmError` **never crosses the boundary**: every public method catches all `HelmError`/`Exception` and maps to `HelmResult`. `Queued` is never returned by `fetchAttributionStatus` (it returns cached `Success(fromCache = true)` or `Failure`).

Fire-and-forget overload body follows the existing `match()`/`increment()` idiom exactly: outer try/catch, `CoroutineScope(Dispatchers.IO).launch { ... }`, inner try/catch + `Log.w(TAG, ...)`; it invokes the suspend variant and discards the result (enqueue-on-transport-failure still happens inside).

> **Overload-resolution risk (flagged):** a suspend and non-suspend function with identical value parameters compile to distinct JVM signatures (Continuation param) so the *declarations* are legal, but Kotlin call sites inside a coroutine can hit "overload resolution ambiguity" (KT-23610 family). Step 8 includes a compile check with a tiny suspend caller in the test source set. Fallback if ambiguous: keep the suspend name and rename the fire-and-forget to `submitOriginalTransactionIdAsync(...)`, documenting the deviation in the PR + a comment on HELM-221 (spec change → comment per pipeline rules).

---

## 4. Path constants + parity test (hard contract)

Add to the existing `Attribution.companion object`, same style/comment block as the current three:

```kotlin
internal const val PATH_PROMO_CODE = "/api/client/v1/attribution/promo-code/"
internal const val PATH_STATUS = "/api/client/v1/attribution/status/"
internal const val PATH_TRANSACTION = "/api/client/v1/attribution/transaction/"
```

Extend `AttributionPathsTest.attributionPathsMatchBackendRoutes()` (or add a second `@Test`) with:

```kotlin
assertEquals("/api/client/v1/attribution/promo-code/", Attribution.PATH_PROMO_CODE)
assertEquals("/api/client/v1/attribution/status/", Attribution.PATH_STATUS)
assertEquals("/api/client/v1/attribution/transaction/", Attribution.PATH_TRANSACTION)
```

These must equal the Helm backend routes registered in `apps/client_api/urls.py` and the iOS `APIPath.swift` additions — exact parity is the contract with the Helm service ticket (trailing slash included; a drift 404s silently, which is why this test exists).

---

## 5. Request bodies + Context handling

**Body composition** (pure `internal fun` builders in `AttributionApi`, unit-tested like `Attribution.buildMatchBody` / `AnalyticsClient.registrationBody`):

| Endpoint | Body |
|---|---|
| `PATH_PROMO_CODE` | `{"user_id": userId, "code": code, "platform": "android", "device_id": deviceId}` |
| `PATH_STATUS` | `{"user_id": userId, "platform": "android", "device_id": deviceId}` |
| `PATH_TRANSACTION` | `{"user_id": userId, "original_transaction_id": id, "platform": "android", "device_id": deviceId}` |

`platform` is the literal `"android"` (define one `private const val PLATFORM = "android"`). `device_id` comes from `AttributionStore.getOrCreateDeviceId(context)` (the shared HELM-202 id) — captured once per bind and passed into `AttributionApi` as a `() -> String` provider.

**Context decision — cached appContext, consistent with `match()`/`increment()`:** the epic-mandated signatures have no `Context` parameter, and the replay-on-configure trigger *itself* needs a Context, so:

1. Add non-breaking overload `Helm.configure(context: Context, publishableKey: String, baseURL: String)` that sets `Configuration.instance` then calls `Attribution.instance.bind(context.applicationContext)`. Keep the 2-arg `configure` (source compatibility); its KDoc points to the 3-arg one as required for offline-queue replay.
2. `Attribution.bind(context)` (internal, idempotent, `@Volatile appContext` + a single-shot flag under a lock): stores `appContext`, lazily constructs `AttributionApi(SharedPrefsStore(context), { AttributionStore.getOrCreateDeviceId(context) }, System::currentTimeMillis)`, registers the foreground observer, and kicks one replay on `Dispatchers.IO`.
3. `match(context)` also calls `bind(context)` (it already caches `appContext`), so existing integrations that call `match` at launch get queue replay without adopting the new configure overload.
4. Public suspend methods with no bound context return `HelmResult.Failure(code = "not_configured")` — they never throw and never queue (there is nowhere to persist).

---

## 6. Offline queue — `PendingSubmissionStore`

**Persistence format** — follows the `KeyValueStore` store pattern (`IdentityStore`-style thin class over the seam). Single JSON array string (via `org.json`, already a dependency of main source) under key `"helm_pending_attribution"` in the `"dev.helmcode.helm.analytics"` prefs file (same `SharedPrefsStore` backing as `DeviceIdStore`/`IdentityStore`):

```json
[
  {"id": "<uuidv4>", "kind": "promo_code", "user_id": "…", "code": "…", "enqueued_at_ms": 1765200000000},
  {"id": "<uuidv4>", "kind": "transaction", "user_id": "…", "original_transaction_id": "…", "enqueued_at_ms": 1765200000000}
]
```

Internal model: `internal data class PendingSubmission(id, kind: Kind, userId, code?, originalTransactionId?, enqueuedAtMs)` with `enum Kind { PROMO_CODE, TRANSACTION }`. Store API: `all(): List<PendingSubmission>` (drops+persists expired entries on read, logging each drop), `enqueue(sub)` (dedupe: replace any entry with the same `(kind, userId, code/originalTransactionId)` key, keeping the older `enqueuedAtMs` — prevents duplicate rows and retention-reset gaming), `remove(id)`, `clear()`. Cap at `MAX_PENDING = 100` entries, dropping oldest (EventQueue precedent). All methods synchronized on an internal lock. Corrupt JSON → log + treat as empty (self-heals on next write).

**Enqueue rules — transport-class failures only.** Classification of `HelmError` (in `AttributionApi`):

| Error | Class | Behavior for submissions |
|---|---|---|
| `NetworkError` (IO/timeout/DNS) | transport | enqueue → `Queued` |
| `ServerError` with status ≥ 500 | transport | enqueue → `Queued` |
| `ServerError` with status 429 | transport | enqueue → `Queued` |
| `InvalidResponse` (2xx, unparseable body) | transport | enqueue → `Queued` (safe: all three endpoints are idempotent under replay per HELM-218) |
| `ServerError` 4xx (≠429) | **terminal** | parse error envelope `{"error": {"message", "code"}}` from `ServerError.message` → `Failure(code, httpStatus, message)`; envelope unparseable → `Failure("http_<status>", status, rawBody)` |
| `NotConfigured` | terminal | `Failure("not_configured")` — never queued |

Server-side validation outcomes (`invalid_code`, `code_inactive`, `already_linked`, `missing_field`, `invalid_token`, `not_found`) all arrive as 4xx → terminal, surfaced, never queued.

**Replay triggers** (all three per epic decision):
1. **Configure** — `Helm.configure(context, …)` → `bind()` → replay launch.
2. **App foregrounding** — `ProcessLifecycleOwner` `DefaultLifecycleObserver.onStart`, registered once in `bind()` using the exact `Handler(Looper.getMainLooper()).post { ... }` idiom from `Analytics.observeLifecycle()` (lifecycle-process 2.7.0 already a dependency — no build change).
3. **Before any new attribution call** — the three new public methods call `replayPending()` first (`match`/`increment` untouched).

**Replay semantics:** single-flight via `kotlinx.coroutines.sync.Mutex` (`tryLock`/skip if already replaying — no pile-up); FIFO by `enqueuedAtMs`; per entry: expired (>30 days) → drop + `Log.w`; POST → success **or terminal failure** → `remove(id)` (a queued promo that fails validation on replay resolves *silently* to unlinked — the app discovers via status fetch, per epic); transport failure → keep entry and **abort the loop** (network is down; later trigger retries). Successful promo-code replay also refreshes the status cache for that userId (a confirmed replay is a status change).

**Retention: 30 days** from `enqueued_at_ms` (`RETENTION_MS = 30L * 24 * 60 * 60 * 1000`), enforced at read time in `all()` with the injected `clock` (testable). Expired entries are dropped and logged, never sent.

**Idempotent replay:** guaranteed server-side (HELM-218: resubmitting the same `(user_id, code)` returns the success payload, not `already_linked`; transaction endpoint is an idempotent append) — the SDK additionally dedupes at enqueue time so the queue never holds two copies of the same logical submission.

**Threading:** replay runs in `CoroutineScope(Dispatchers.IO).launch { ... }` from non-suspend triggers (matching `match()`/`increment()` style); from suspend methods it is just awaited inline (already on IO via `HelmHttpClient`).

---

## 7. Status cache — `AttributionStatusCache`

- `KeyValueStore`-backed, key `"helm_attribution_status_cache"`, JSON **object keyed by userId** (per-userId as the ticket requires; multiple identities on one device don't cross-contaminate):
  `{"<userId>": {"linked": true, "influencer_code": "…", "offering_id": "…", "fetched_at_ms": …}}`
- API: `get(userId): CachedStatus?`, `put(userId, status)`, `clear()`. Synchronized; corrupt JSON → empty.
- `fetchAttributionStatus` flow: replay queue → POST `PATH_STATUS` → on success: `put` + return `Success(AttributionStatus(…, fromCache = false))`; on **transport-class** failure: cached entry for that userId? → `Success(AttributionStatus(…, fromCache = true))`, else `Failure("network_error")`; on **terminal** failure: `Failure(code, httpStatus, message)` (no cache fallback — the server answered).
- Cache refresh points: successful fetch; successful promo-code submit (`{linked: true, influencer_code, offering_id}` is a complete status → write it); successful promo replay.
- **Wipe on identity clear:** `Attribution.reset()` (public) clears queue + cache; `Analytics.clearIdentity()` calls internal `Attribution.instance.onIdentityCleared()` → same wipe (no-op with a log if never bound). Match state / device id are untouched.

---

## 8. HelmHttpClient — no changes (confirmed)

All three endpoints are POST (HELM-218 explicitly chose POST because both SDK clients are POST-only). `HelmHttpClient.post` already: sends `Authorization: Bearer pk_…`, JSON-encodes `Map<String, Any?>`, returns parsed `Map`, and surfaces non-2xx as `ServerError(status, rawErrorBody)` — the raw body is exactly what the error-envelope parser needs. Timeouts (10s/15s) fine. **No modification.**

---

## 9. Versioning / JitPack release

- Bump the publication fallback in `helm/build.gradle.kts` `afterEvaluate` block: `version = project.findProperty("VERSION_NAME") as String? ?: "0.5.0"`.
- `jitpack.yml` unchanged (`openjdk17`, `:helm:publishToMavenLocal`; JitPack substitutes the requested tag as the version).
- After merge to `main` (human review gate): tag `v0.5.0` on the merge commit, push tag. Consumers: `com.github.Helm-Development:Helm-Android-SDK:v0.5.0`. Prime the JitPack build by requesting the artifact once.
- Do **not** tag from the task branch; tagging happens post-merge (release prep only in this ticket: version string + PR note).

---

## 10. Test plan

Test framework: JUnit4 + mockito-kotlin + MockWebServer (all present). MockWebServer pattern: set `Configuration.instance = Configuration("pk_test", server.url("/").toString().trimEnd('/'))` in `@Before`, null it in `@After` (per `HelmHttpClientTest`/`AttributionStoreTest` cleanup conventions); construct `AttributionApi(InMemoryStore(), { "device-uuid" }, { fixedNowMs })` directly — no Context, no Robolectric.

**`AttributionPathsTest.kt` (modify)** — the three new exact-string assertions (§4). Written first, fails until constants exist.

**`PendingSubmissionStoreTest.kt` (new)**
1. enqueue → `all()` round-trips through JSON (both kinds, all fields).
2. dedupe: same `(kind, userId, code)` enqueued twice → one entry, original `enqueuedAtMs` kept.
3. retention: entry with `enqueuedAtMs = now − 31d` dropped by `all()`; `now − 29d` kept (fixed clock).
4. cap: 101st entry evicts oldest.
5. corrupt stored JSON → `all()` empty, subsequent enqueue works.
6. `clear()` empties.

**`AttributionStatusCacheTest.kt` (new)** — put/get round-trip; per-user isolation (`get("other")` null); overwrite refreshes; `clear()` wipes; corrupt JSON tolerated.

**`AttributionApiTest.kt` (new, MockWebServer)**
1. `submitPromoCode` 200 `{"linked": true, "influencer_code": "anna", "offering_id": "off_1"}` → `Success(PromoCodeLink("anna", "off_1"))`; `RecordedRequest` asserts path `== PATH_PROMO_CODE`, `Authorization: Bearer pk_test`, body contains `user_id`, `code`, `platform: "android"`, `device_id`.
2. **Terminal vs transport:** 400 `{"error": {"code": "invalid_code", "message": "…"}}` → `Failure("invalid_code", 400, …)` and **queue empty**; 500 → `Queued` and queue has one entry; disconnect (`SocketPolicy.DISCONNECT_AT_START`) → `Queued`; 429 → `Queued`; 401 `invalid_token` → `Failure`, not queued.
3. **Queue replay:** pre-seed a pending promo entry; enqueue 200 responses; call `fetchAttributionStatus` → server received the replayed promo POST **before** the status POST; queue drained.
4. **Replay terminal resolution:** pending entry + 400 `invalid_code` on replay → entry removed, no crash, replay loop continues to the status call.
5. **Replay transport abort:** two pending entries + 500 → first entry retained, second never sent (loop aborted).
6. **Retention expiry on replay:** pending entry 31 days old (fixed clock) → dropped, zero requests for it.
7. **Status cache:** fetch 200 `{linked: true, …}` → `Success(fromCache = false)` + cached; then disconnect → `Success(fromCache = true)` with same values; different userId under disconnect → `Failure("network_error")`; terminal 401 → `Failure`, cache untouched.
8. `submitOriginalTransactionId` 200 → `Success(Unit)`; 500 → `Queued` with `kind = transaction` persisted.
9. `reset()`/identity-clear wipe: seed queue + cache → wipe → both empty.
10. Not-configured: `Configuration.instance = null` → `Failure("not_configured")`, nothing queued.
11. Compile check for the suspend/non-suspend overload pair: a test helper calling both from suspend and non-suspend contexts (see §3 risk; drives the fallback decision).

Body-builder purity tests (promo/status/transaction maps) either in `AttributionApiTest` or a small `AttributionBodiesTest`, mirroring `AnalyticsClientTest`.

---

## 11. Ordered implementation steps

1. **Ticket hygiene:** HELM-221 already In Progress. Copy this plan to `docs/superpowers/plans/2026-08-08-helm-221-attribution-methods.md` in the worktree (repo convention).
2. **Failing parity test first:** extend `AttributionPathsTest`; run — fails. Add the three constants to `Attribution` — passes. (Mirrors the HELM-215/path-fix TDD precedent.)
3. **Public types:** `HelmResult.kt` (HelmResult, PromoCodeLink, AttributionStatus).
4. **`PendingSubmissionStore`** + `PendingSubmissionStoreTest` (test-first).
5. **`AttributionStatusCache`** + `AttributionStatusCacheTest`.
6. **`AttributionApi`:** body builders, `HelmError` classification, `submitPromoCode`/`fetchStatus`/`submitTransaction`, `replayPending()` with Mutex single-flight; grow `AttributionApiTest` case-by-case (terminal/transport → queue → replay → retention → cache).
7. **Wire the facade:** `Attribution.bind(context)` + public suspend methods + fire-and-forget overload + `reset()` + foreground observer; `Helm.configure(context, …)` overload; `match(context)` calls `bind`; `Analytics.clearIdentity()` → `onIdentityCleared()`.
8. **Overload compile check** (§3 risk); apply fallback naming if needed and comment on HELM-221.
9. **KDoc:** RevenueCat app-user-ID alignment note on all three methods + `configure`; offline-queue semantics on `submitPromoCode` (queued outcome, silent replay-failure resolution).
10. **Version bump** in `helm/build.gradle.kts` → `0.5.0`.
11. **Full verification** (§12), self-review, then PR `[HELM-221] Add attribution methods with offline queue and status cache` with the YouTrack section/body per `development:pr-and-commits`; update ticket description with `**PR**: [#N](url)`, post PR comment, move HELM-221 → Review/To Verify with a comment.

## 12. Verification

```bash
export JAVA_HOME="/Users/robertbarber/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd /Users/robertbarber/Projects/Helm/worktrees/HELM-221-android-sdk
./gradlew :helm:testDebugUnitTest --tests "dev.helmcode.helm.attribution.*"   # focused
./gradlew :helm:test                                                          # full regression
./gradlew :helm:assembleRelease                                               # publishable AAR sanity
```

## 13. Risks

1. **Suspend/non-suspend same-name overload ambiguity** (KT-23610 class) — mitigated by compile check + documented fallback rename (§3). Highest-probability deviation from the epic's literal signature.
2. **Context availability:** apps on the legacy 2-arg `configure` that never call `match(context)` get no replay-on-configure/foreground and `Failure("not_configured")` from the new methods. Mitigated by the 3-arg overload + `match()` binding + KDoc; TAS-796 integration should adopt the 3-arg form.
3. **Backend not yet live:** the three endpoints ship in the Helm service ticket (created before client tasks per pipeline). Path strings are the frozen contract; MockWebServer keeps SDK tests independent, but do one manual staging smoke test before tagging v0.5.0 if the backend is deployed.
4. **Replay/enqueue races:** two concurrent submissions during a replay — Mutex single-flight for replay + synchronized store methods; worst case a duplicate POST, which the idempotent endpoints absorb.
5. **SharedPreferences JSON growth:** bounded by the 100-entry cap + 30-day expiry.
6. **Silent replay-failure UX** (queued code later invalid resolves to unlinked with no callback) — by design per HELM-218; deferred-failure UX is the app's concern (TAS-796). Document in KDoc so it isn't reported as a bug.
7. **iOS parity drift:** result-type naming differs by platform (Kotlin `HelmResult` vs iOS throws) — acceptable per epic; the *paths and body keys* are the cross-platform contract, guarded by `AttributionPathsTest` and body tests.
