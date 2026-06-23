# Android SDK Attribution Path Fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Android SDK post attribution requests to the backend's real routes (`/api/client/v1/attribution/*`), matching the iOS SDK and the Helm backend, with a regression guard.

**Architecture:** Extract the three attribution paths in `Attribution.kt` into named companion constants with the corrected `/api/client/v1/attribution/*` values; reference them at the three call sites; assert the constant values in a new unit test.

**Tech Stack:** Kotlin, Android `:helm` library module, JUnit4.

**Spec:** `docs/superpowers/specs/2026-06-23-android-attribution-path-fix-design.md`

**Test command (this task):** `./gradlew :helm:testDebugUnitTest --tests "dev.helmcode.helm.attribution.AttributionPathsTest"`
**Regression:** `./gradlew :helm:testDebugUnitTest`

---

## File Structure

- **Modify** `helm/src/main/kotlin/dev/helmcode/helm/attribution/Attribution.kt` — add 3 path constants to the existing companion object; replace the 3 inline path literals at lines 105/132/164 with the constants.
- **Create** `helm/src/test/kotlin/dev/helmcode/helm/attribution/AttributionPathsTest.kt` — assert each constant equals the backend/iOS path.

The companion object today is:
```kotlin
    companion object {
        private const val TAG = "HelmSDK"

        internal val instance: Attribution by lazy { Attribution() }
    }
```

---

## Task 1: Correct the attribution paths

**Files:**
- Create: `helm/src/test/kotlin/dev/helmcode/helm/attribution/AttributionPathsTest.kt`
- Modify: `helm/src/main/kotlin/dev/helmcode/helm/attribution/Attribution.kt`

- [ ] **Step 1: Write the failing test**

Create `helm/src/test/kotlin/dev/helmcode/helm/attribution/AttributionPathsTest.kt`:

```kotlin
package dev.helmcode.helm.attribution

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the attribution endpoint contract: these paths MUST equal the Helm
 * backend routes (apps/client_api/urls.py, mounted at /api/) and the iOS SDK.
 * A prefix-less path (e.g. "/attribution/match/") 404s and is silently
 * swallowed, recording no attribution.
 */
class AttributionPathsTest {
    @Test
    fun attributionPathsMatchBackendRoutes() {
        assertEquals("/api/client/v1/attribution/referrer/", Attribution.PATH_REFERRER)
        assertEquals("/api/client/v1/attribution/match/", Attribution.PATH_MATCH)
        assertEquals("/api/client/v1/attribution/event/", Attribution.PATH_EVENT)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :helm:testDebugUnitTest --tests "dev.helmcode.helm.attribution.AttributionPathsTest"`
Expected: FAIL — `Attribution.PATH_REFERRER` / `PATH_MATCH` / `PATH_EVENT` are unresolved (compilation error).

- [ ] **Step 3: Add the constants to the companion object**

In `Attribution.kt`, replace the companion object with:

```kotlin
    companion object {
        private const val TAG = "HelmSDK"

        internal val instance: Attribution by lazy { Attribution() }

        // Backend routes (apps/client_api/urls.py, included at /api/). Must match
        // the iOS SDK and the Helm backend exactly, or attribution requests 404.
        internal const val PATH_REFERRER = "/api/client/v1/attribution/referrer/"
        internal const val PATH_MATCH = "/api/client/v1/attribution/match/"
        internal const val PATH_EVENT = "/api/client/v1/attribution/event/"
    }
```

- [ ] **Step 4: Use the constants at the three call sites**

In `Attribution.kt`, replace the inline path literals (currently lines ~105, ~132, ~164):

`HelmHttpClient.post("/attribution/referrer/", body)` → `HelmHttpClient.post(PATH_REFERRER, body)`

`HelmHttpClient.post("/attribution/match/", body)` → `HelmHttpClient.post(PATH_MATCH, body)`

`HelmHttpClient.post("/attribution/event/", body)` → `HelmHttpClient.post(PATH_EVENT, body)`

These calls are inside instance methods (`performMatch`, `performIncrement`); the constants live on the companion, so reference them bare (`PATH_REFERRER`) — they resolve via the companion from within the class. No other change to those lines (the `body` argument is unchanged).

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :helm:testDebugUnitTest --tests "dev.helmcode.helm.attribution.AttributionPathsTest"`
Expected: PASS (1 test).

- [ ] **Step 6: Run the full module suite for regressions**

Run: `./gradlew :helm:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — all existing tests still pass (DeviceIdStoreTest, AnalyticsTest, AnalyticsClientTest, StoresTest, AttributionStoreTest, etc.).

- [ ] **Step 7: Commit**

```bash
git add helm/src/main/kotlin/dev/helmcode/helm/attribution/Attribution.kt \
        helm/src/test/kotlin/dev/helmcode/helm/attribution/AttributionPathsTest.kt
git commit -m "fix: post attribution to /api/client/v1/attribution/* (match backend + iOS)"
```

---

## Notes for the implementer

- **Gradle needs `JAVA_HOME`.** If `./gradlew` reports "Unable to locate a Java Runtime", set it first (a JDK is installed at `/opt/homebrew/Cellar/openjdk/*/libexec/openjdk.jdk/Contents/Home`): `export JAVA_HOME="$(/usr/libexec/java_home)"` then re-run.
- **Only paths change.** Do not touch request bodies, `HelmHttpClient`, analytics paths (already correct), or any behavior.
- **Out of scope:** tagging v0.3.0 and the TAS-692 app changes are separate follow-on steps handled after this merges.
