# Android SDK — Fix attribution endpoint paths

**Date:** 2026-06-23
**Repo:** `Helm/sdk-android`
**Relates to:** HELM-205 item #3 (path-prefix reconcile); prerequisite for TAS-692 and the v0.3.0 release.

## Problem

The Android SDK posts attribution requests to paths the Helm backend does not serve. `Attribution.kt` calls:

- `HelmHttpClient.post("/attribution/referrer/", …)` (line 105)
- `HelmHttpClient.post("/attribution/match/", …)` (line 132)
- `HelmHttpClient.post("/attribution/event/", …)` (line 164)

`HelmHttpClient` builds the URL as `config.baseURL.trimEnd('/') + path`, so with `baseURL = https://helmcode.dev` these resolve to `https://helmcode.dev/attribution/match/` etc. The backend mounts these views under `/api/client/v1/attribution/...` (`apps/client_api/urls.py`, included at `path('api/', …)`):

- `/api/client/v1/attribution/match/`
- `/api/client/v1/attribution/referrer/`
- `/api/client/v1/attribution/event/`

So every attribution call **404s and is silently swallowed** (the SDK catches and logs). No attribution is ever recorded. Analytics is unaffected — it already uses the correct `/api/v1/analytics/...` paths.

The **iOS SDK is correct** and is the reference: it posts to `/api/client/v1/attribution/match/` and `/api/client/v1/attribution/event/`. Android is the lone outlier.

| Call | Android (now) | iOS / backend (correct) |
|---|---|---|
| match | `/attribution/match/` | `/api/client/v1/attribution/match/` |
| referrer | `/attribution/referrer/` | `/api/client/v1/attribution/referrer/` |
| event | `/attribution/event/` | `/api/client/v1/attribution/event/` |

## Goal

Android attribution requests hit the backend's real routes, matching iOS and the backend URL conf, and a test guards against regression.

## Non-goals

- Changing analytics paths (already correct).
- Changing `HelmHttpClient`, the request bodies, or any behavior other than the URL path.
- App-side changes (TAS-692) or the v0.3.0 tag (separate follow-on steps).

## Design

Extract the three attribution paths into named constants on `Attribution`'s companion object with the corrected values, and reference them at the call sites. Constants make the contract explicit and unit-testable without driving the Android-heavy `match()` flow.

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

Call sites become `HelmHttpClient.post(PATH_REFERRER, body)`, `HelmHttpClient.post(PATH_MATCH, body)`, `HelmHttpClient.post(PATH_EVENT, body)`.

(`TAG` and `instance` already exist in the companion; only the three constants are added.)

## Components touched

| File | Change |
|---|---|
| `helm/src/main/kotlin/dev/helmcode/helm/attribution/Attribution.kt` | add 3 path constants; replace 3 inline literals with them |
| `helm/src/test/kotlin/dev/helmcode/helm/attribution/AttributionPathsTest.kt` *(new)* | assert each constant equals the backend/iOS path |

## Testing (JUnit4)

`Attribution.PATH_*` are `internal`, so a test in the same module/package can read them. The test documents and guards the cross-component contract:

```kotlin
package dev.helmcode.helm.attribution

import org.junit.Assert.assertEquals
import org.junit.Test

class AttributionPathsTest {
    @Test
    fun attributionPathsMatchBackendRoutes() {
        assertEquals("/api/client/v1/attribution/referrer/", Attribution.PATH_REFERRER)
        assertEquals("/api/client/v1/attribution/match/", Attribution.PATH_MATCH)
        assertEquals("/api/client/v1/attribution/event/", Attribution.PATH_EVENT)
    }
}
```

This is a regression guard, not a behavioral test — it locks the paths to the backend contract so a future edit can't silently revert to the prefix-less form. Driving `match()` end-to-end requires Android Install-Referrer/Context infrastructure and is out of unit scope; correctness of the wiring is covered by the existing full suite compiling + this assertion.

## Risks

- Low. Three string literals behind named constants; no behavior change beyond the corrected URL. Full unit suite must stay green.
- After merge, this must be included in the **v0.3.0** tag the app bumps to (sequencing handled outside this change).
