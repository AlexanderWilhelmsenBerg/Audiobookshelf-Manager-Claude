# Garmin provider and Android device controls — 2026-10-08

Requirements SET-002, DL-001/003, AUTH-002/003/005, PLAY-007, SYNC-001/002; PD008.
Implementation and build evidence are recorded separately from hardware acceptance.

Implemented: separate native Audio Provider, reviewed MIT WatchShelf engine/Sidecar contracts,
profile/account anchors, durable queue and paged local event journal, Room22 migration/import/outbox,
one SDK owner with separate app channels, inline Playback Devices controls/dialogs and protected
PHONE/GARMIN publishers. No phone-byte transfer, remote autoplay, destructive upgrade reset, normal ABS
token fallback or watch face is introduced. Existing owner WIP in the primary checkout is untouched.

Local focused Android verification passes all37 Garmin tests, including rendered inline controls, durable request deduplication, original-time import-before-ACK, failed-import/no-ACK, privacy and shared golden responses. The A-B-A guard test fails when the actual generation protection is removed; the fix is restored. Both supported targets, test-enabled compilation and IQ export pass locally for both apps with SDK9.2 gradual checking level1. Guardrails/Sidecar/transport fixture generation pass. An earlier native RNE execution passed49 tests; final reruns stalled in the simulator launcher and are not recorded as passes. The new failed-read safety test is compiled, with execution still pending. The full strict gate passes locally; GitHub records the subsequent exact PR/main source and CI identity. Earlier failures
(gradual-type errors, string complication numeric ranges, strict formatting/compiler issues) were found
before delivery; their logs are retained outside source. No phone or physical watch test was run here.
GD01–10, GF01–07, existing G01/G02 and Q01/A08 remain NOT RUN.

## Additional physical coverage logged during implementation — NOT RUN

- GD-03/08: first-pair code and explicit account/server check; existing unbound cache refuses pairing;
  same-account reauthentication succeeds, changing username/server refuses retained media/event rebinding.
- GD-04/05: a remaining-audio suffix reports its start position and requested part count; replaying before
  that position requires a full download. Native Wi-Fi battery/storage/charging, interrupted transcode,
  cached corruption, reconnect and reboot retain valid playable parts and exact resume position.
- GD-06/07: event ACK loss, duplicate pages, original event times and rewind; failed phone persistence
  leaves the watch journal pending. Failed ABS refresh retries even after native sync time is unchanged.
  Distinguish accepted request, actual completed native sync and failed sync IDs.
- GD-08/10: open/close provider while phone is locked/switched; ready/sleeping wake/redact correctly.
  Closed provider is not repeatedly polled. Multiplex both apps through one Mobile SDK, Garmin Connect
  lifecycle, second watch, delayed packets, fresh install and retained-key upgrade without data resets.
- GF-01/03/04: protected consumer with same retained key vs different key; absent/uninstalled publisher,
  delivered privacy clearing, offline limitations, close/reboot/freshness and sustained battery cadence.

Record watch model/firmware, GCM and Sidecar versions, source revisions, APK/PRG hashes and developer
public-key fingerprint. UI appearance/200% localized long titles requires visual judgment; functional
gestures, selections, privacy and durable state should be automated where hardware tools permit.

## Verified implementation delivery — 2026-10-08

Garmin provider/publishers are merged in [PR10](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/pull/10), commit `19cc9a67db505fb9389a8861373d97b3c037072b`. All nine PR CI checks pass at `e6829ade`; main CI37699273330 also passes. Matching Android device controls pass full strict `verifyDebug -Pshelfplayer.warningsAsErrors=true` locally: 1024 tasks, BUILD SUCCESSFUL in2m50s. All37 Garmin tests and38 Room migration tests pass, with actual-source A-B-A reversion proof. The bridge uses Room22 and adds no external dependency/classpath change. Its implementation branch is `codex/garmin-provider-controls`; GitHub PR delivery remains authoritative.

[Garmin testing artifacts](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/actions/runs/37699273330) contain both supported target PRGs and packages. The [installation guide](../garmin-install-for-testing.md) and GD/GF register cover testing; all physical cases remain NOT RUN. Final simulator reruns stalled, so the earlier49 native passes do not establish final-source/native or hardware acceptance.
