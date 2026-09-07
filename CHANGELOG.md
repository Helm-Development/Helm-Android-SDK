# Changelog

All notable changes to the Helm Android SDK are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-09-07

The SDK leaves alpha. There is no functional change from 0.7.0 — the code is
identical. The version number is the announcement.

What it commits to: from here on the public API follows semantic versioning
properly. A breaking change requires a major version, so you can depend on a
`1.x` range instead of pinning an exact version. Under `0.x` any release was
free to break you.

Upgrading from 0.7.0 needs no code changes.

## [0.7.0] - 2026-09-07

`debug` now rides on every request the SDK makes, and a new `environment` label
travels with analytics. Additive and backwards-compatible — both parameters are
defaulted, so existing `Helm.configure(...)` call sites keep compiling unchanged
and a build that sets neither still reports `debug: false` and
`environment: "production"`.

### Added
- HELM-238: installation registration now sends the configured `debug` boolean,
  the same wire field the attribution endpoints already take. Helm leaves activity
  from a debug build out of its daily, weekly, and monthly active-user counts, so
  a developer running the app all day no longer shows up as a real user. The flag
  is sent on every registration, including when `false`, so a device that moves
  from a debug build to a shipped one counts as live again.
- HELM-242: `environment` parameter on both `Helm.configure(...)` overloads. It is
  a free-form label for the build's deployment environment (for example
  `"production"`, `"staging"`, `"development"`) and defaults to `"production"`. It
  is recorded on analytics registration and on every analytics event, so activity
  can be filtered by environment. It is independent of `debug`: `debug` says
  whether the activity counts as real usage at all, `environment` only says which
  deployment it came from.
- HELM-242: every object in the analytics `events[]` array now carries its own
  `debug` and `environment`, captured when the event is created rather than when
  the batch is flushed. An event queued under one configuration keeps its own
  values even if the app is reconfigured before the flush — the same rule TAS-801
  already applies to queued attribution submissions.
- HELM-242: the two older attribution requests, `match` and `event`, now send
  `debug` too. The three influencer endpoints (promo-code, status, transaction)
  already did, so all five attribution requests now carry the marker.
  `environment` is analytics-only and is not sent on any attribution request.

## [0.6.0] - 2026-08-10

### Added
- TAS-801: `debug` parameter on `Helm.configure(...)`. When `true`, every
  influencer-attribution submission this build makes (promo-code, status,
  transaction) is registered in Helm as sandbox test data and excluded from
  payouts. Defaults to `false`. A submission queued offline replays with the
  marker it was made under.

[1.0.0]: https://github.com/Helm-Development/Helm-Android-SDK/compare/0.7.0...1.0.0
[0.7.0]: https://github.com/Helm-Development/Helm-Android-SDK/compare/0.6.0...0.7.0
[0.6.0]: https://github.com/Helm-Development/Helm-Android-SDK/compare/0.5.0...0.6.0
