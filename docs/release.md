# Release

`PRODUCT_SPEC 18` defines the pipeline and `PRODUCT_SPEC 25` the acceptance checklist. Phase 0 is not
releasable; this records the process and what still blocks it.

## Blocking open decisions

From `PRODUCT_SPEC 24`, each of these must be resolved by an ADR before a public build:

1. Final app name and application ID — the placeholder `com.example.shelfplayer` must be replaced
   (`PRODUCT_SPEC 16.4`).
2. Licence — see `LICENSE`; unresolved, and it gates distribution rather than development.
3. Distribution channel (Play, F-Droid, GitHub Releases, private).
4. Minimum supported Audiobookshelf server version — `docs/api-compatibility.md` currently lists
   none.
5. Whether true source-file deletion exists in the chosen server version and can be safely exposed
   (`MGR-006`).

## Versioning

`versionCode` and `versionName` live in the application convention plugin. `PRODUCT_SPEC 18` requires
a reproducible version code; the derivation rule is chosen when a distribution channel is.

## Signing

There is no signing configuration in this repository and there must not be one. Release builds
produced by CI are unsigned. Signing happens only in a protected environment holding the key
material, never in a workflow triggered by a push (`PRODUCT_SPEC 18`).

## The pipeline today

`.github/workflows/pull-request.yml` — Gradle wrapper validation, secret scan, `verifyDebug` with
warnings-as-errors, Room schema diff, debug APK, dependency report.

`.github/workflows/main.yml` — the above plus release lint and an unsigned release assembly.

## What must be added before a release

| Step | Requirement | Blocked on |
| --- | --- | --- |
| Managed-device tests | 18, 17.2 | UI worth testing on a device (Phase 1) |
| Audiobookshelf container integration tests | 17.1 | Endpoints to test (Phase 1) |
| Software Bill of Materials | 18 | Licence decision |
| Dependency vulnerability scan | 18 | — can be added at any time |
| Mapping/native-symbol archive | 18 | A signed release build |
| Changelog generated from labelled changes | 18 | Label convention |
| Two-hour playback soak, offline sync test | 25 | Phases 2 and 3 |

## Pre-release checklist

Use `PRODUCT_SPEC 25` verbatim. Do not mark an item complete on the strength of a happy-path screen;
`PRODUCT_SPEC 21` requires error, loading, empty, offline and permission states, accessibility
semantics, and evidence that nothing private is logged.
