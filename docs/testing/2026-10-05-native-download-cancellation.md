# Native download verification cancellation — 2026-10-05

Requirements DL-001/002, spec12/17.2/21; PR #232; selected DC-P03 boundary coverage.
Base d99c772d, Samsung SM-S928B/API36. Production downloader source is unchanged by the harness.
Exact source/test/APK digests and numeric gates are in
[the evidence record](evidence/native-download-cancellation-2026-10-05.json).

Three instrumented tests run in the isolated library test package, using an in-memory real Room
database, real private files and AndroidMediaContainerVerifier/MediaMetadataRetriever. Synthetic
one-second PCM WAV and invalid bytes require no external media, owner account or server.
A latch holds the synchronous verifier after native parsing; a separate parent cancels the owning
child Job before releasing it. The native parser result is asserted before cancellation.

| Boundary | Required/observed outcome | Fixed / actual source reverted / restored |
| --- | --- | --- |
| Fresh valid body | Part bytes and response metadata retained; no final rename; Paused book/Queued file. | PASS / rename guard FAIL / PASS |
| Valid complete416 part | Existing validated part remains resumable; no final rename. | PASS / rename guard FAIL / PASS |
| Invalid complete416 part | Part retained; no deletion or replacement request after cancellation. | PASS / deletion guard FAIL / PASS |

Each case checks exact part bytes/metadata, an unchanged committed sibling, and explicit later Resume
through the ordinary native validation/commit path. Both actual-source rename failures and the deletion
failure are preserved; FileDownloader is restored byte-for-byte before the final gate.

Formatter, full strict verifyDebug and connected tests PASS with--rerun-tasks:6m46s,1,157 tasks,
all executed. Forced execution is required because instrumentation classpaths changed. Three restored
device tests pass with zero failures/errors/skips. The initial missing test-only Room dependency and
the regression-summary XML parsing error are corrected; their ignored logs are retained.

Run with an attached device:

```bash
./gradlew :data:downloads:connectedDebugAndroidTest
```

The harness is discovered by the ordinary AndroidJUnitRunner connected task. Caller audit retains
BookDownloadWorker→BookDownloader→FileDownloader and both real verification boundaries. No production
hook, endpoint/response field, schema, authentication or permission change is introduced.

Scope limits: the transfer response is a typed fake; this does not accept HTTP Range/If-Range on the
wire, actual WorkManager stop scheduling, process kill/reboot, notification/UI progress, shared claims,
storage failure/removable hardware or heard playback. Those matrix portions remain pending, and no
issue is closed or PR merged by these three cases. Neither native instrumentation task runs in CI;
strict verification compiles/checks them but requires separate physical execution.
