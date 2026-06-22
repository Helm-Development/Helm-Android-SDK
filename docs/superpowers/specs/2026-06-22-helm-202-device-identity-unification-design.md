# HELM-202 — Unify device identity & close the attribution→analytics join (Android SDK)

**Date:** 2026-06-22
**Repo:** `Helm/sdk-android`
**Ticket:** HELM-202 (epic HELM-85; cross-project epic TAS-690)
**Scope:** ID unification (#1) + attribution↔analytics join (#2) only. The logging module (#3) and endpoint-prefix reconciliation (#4) from the ticket are explicit follow-ups, not part of this design.

## Problem

The SDK mints and persists **two independent device UUIDs** that never connect:

- Attribution: `AttributionStore.getOrCreateDeviceId()` → SharedPreferences file `helm_sdk_prefs`, key `helm_device_id`. Sent in `/attribution/match/` and `/attribution/referrer/` bodies (`Attribution.kt:86,127`).
- Analytics: `InstallationStore.installationId()` → SharedPreferences file `dev.helmcode.helm.analytics`, key `helm_installation_id`. Sent as `installation_id` on registration and every event batch.

Separately, the backend (HELM-205) binds `Attribution → Installation` when an installation registration includes an `attribution_token`, but `AnalyticsClient.registrationBody()` (`AnalyticsClient.kt:45-53`) **never sends the stored `attribution_id`**. Net effect: even when attribution succeeds, it is never bound to the analytics installation, so the `device_id → user → attribution` join the redesign depends on cannot resolve. (Observed downstream: `elysia` tracking link = 57 clicks / 0 attributed installs.)

## Goals

1. A single, canonical on-device identifier used by both modules.
2. The stored attribution result (`attribution_id`) is delivered to the backend as `attribution_token` so the `Attribution → Installation` FK is closed.
3. No already-provisioned install is orphaned server-side.

## Non-goals

- Ephemeral logging module (HELM-202 #3) — follow-up.
- Endpoint-prefix reconciliation `/attribution/*` vs `/api/v1/analytics/*` (HELM-202 #4, coupled to HELM-205) — follow-up.
- Any change to the backend (tracked in HELM-205).

## Design

### Component 1 — Shared device-identity store

Add an internal `dev.helmcode.helm.identity.DeviceIdStore` that is the single source of truth for the device id and is readable by **both** modules **without** requiring `Analytics.start()` (attribution runs at app launch, before start).

**Canonical value & migration.** On first read, resolve in priority order and then persist the result to the shared key (both legacy keys become read-only after migration):

1. Existing analytics `installation_id` (`dev.helmcode.helm.analytics` / `helm_installation_id`) if present → **adopt verbatim**. This is the value the backend already keyed installation registrations on, so existing analytics installs keep their server identity.
2. Else existing attribution `helm_device_id` (`helm_sdk_prefs`) if present → adopt.
3. Else mint a new lowercase UUIDv4.

**Thread-safety.** Resolution/creation is `@Synchronized` (mirrors current `InstallationStore`) so first-launch races cannot mint two different ids.

**Delegation.** `InstallationStore.installationId()` and `AttributionStore.getOrCreateDeviceId()` both return `DeviceIdStore`'s value. Payload field names are unchanged (`installation_id` in analytics, `device_id` in attribution) — both now carry the same value.

### Component 2 — Close the join via `attribution_token`

- `AnalyticsClient.registrationBody(installationId, userHash, device, attributionToken)` gains an optional `attribution_token`, included **only when non-empty**.
- `Analytics.register()` reads the stored `attribution_id` (via `AttributionStore.getAttributionId`) and passes it as `attribution_token`. **Policy: always send it when stored** — the backend bind is idempotent, so re-sending on every registration is harmless and self-healing (survives an analytics-state reset where the install row is recreated but the attribution is still locally known).
- **Re-register-on-match hook.** When attribution persists a successful match (`AttributionStore.storeMatch`), it triggers an analytics re-register through an internal hook (mirrors the existing `identify()` → `register()` path). This makes the join robust to launch ordering:
  - match completes **before** `start()` → the first `register()` already reads and sends the token.
  - match completes **after** `register()` → the hook fires a second `register()` carrying the token.
  - **no match** → no token stored; hook not fired; nothing sent.

Attribution continues to send the (now shared) id as `device_id` in `/match/` and `/referrer/` bodies for backcompat; the authoritative join is the FK closed by `attribution_token`.

### Data flow (happy path, fresh install from a tracking link)

1. Launch → `Attribution.match()` reads shared `device_id`, posts `/attribution/referrer/` (or `/match/`), stores returned `attribution_id`, fires re-register hook.
2. `Analytics.start()` → `register()` posts `/api/v1/analytics/installations/` with `installation_id` (== shared device id) **and** `attribution_token`.
3. Backend closes `Attribution.installation` FK → subsequent analytics events (carrying `installation_id`) join through to the attribution and, after `identify()`, to the hashed user.

## Components touched

| File | Change |
|---|---|
| `identity/DeviceIdStore.kt` *(new)* | shared id + migration + `@Synchronized` |
| `analytics/InstallationStore.kt` | delegate `installationId()` to `DeviceIdStore` |
| `attribution/AttributionStore.kt` | `getOrCreateDeviceId()` delegates to `DeviceIdStore`; `helm_device_id` legacy read-only |
| `analytics/AnalyticsClient.kt` | `registrationBody(..., attributionToken)`; emit `attribution_token` when non-empty |
| `analytics/Analytics.kt` | `register()` reads attribution_id; internal re-register hook |
| `attribution/Attribution.kt` | invoke re-register hook on `storeMatch` |

## Testing (TDD)

- **`DeviceIdStore` migration:** each of the three resolution branches; result persisted; idempotent across repeated reads; thread-safe under concurrent first-read.
- **Identity stability:** an install with a pre-existing analytics `installation_id` keeps that exact value after migration (no orphaning).
- **`registrationBody`:** includes `attribution_token` when set; omits the key when empty/absent.
- **Re-register hook:** fires exactly once on match success; does **not** fire on no-match.
- **Regression:** existing `StoresTest`, `AttributionStoreTest`, `AnalyticsClientTest`, `AnalyticsTest` continue to pass.

## Risks

- **Cross-module dependency direction.** Attribution must read the shared store without depending on the analytics module's lifecycle. `DeviceIdStore` lives in a neutral `identity` package depended on by both; it takes only a `Context`/`KeyValueStore`, no `start()` requirement.
- **Migration correctness.** If both legacy keys somehow exist with different values, priority (analytics first) is deterministic and documented; analytics is preferred because it is the value the backend already trusts.
