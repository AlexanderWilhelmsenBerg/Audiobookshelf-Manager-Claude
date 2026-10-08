# Watch setup, book-details errors and button guidance — 2026-10-08

**Superseded current findings:** New owner report narrows admission to `files.duration` (-20001),
and confirms Companion clipping. [Follow-up and WL01–08 tests](2026-10-08-garmin-watch-layout.md). Prior WD observations
remain historical evidence; use the newer labelled build for current retests.

## Owner findings

Reported installed build identity/firmware/Sidecar version is unverified. The owner saw “Pair with
bookwave app?”; that wording is absent from the current source. The provider's current code-confirmation
menu is separate from any Garmin platform permission prompt. Do not infer which prompt was displayed.
Phone Send setup failed with a generic connection/Sidecar/account message and no useful watch status.
On-watch login and catalogue browsing worked for the owner's account. Download failed **immediately**,
before Wi-Fi audio transfer, with clipped “onse type. Check sidec” and (-1002). Companion showed no
snapshot and misleading “Stored; waiting for phone”, without button guidance.

These are physical **FAIL** observations for setup, download admission and error readability; catalogue
access is an owner-reported path pass, not complete authentication/offline-playback acceptance. The old
GS/GD registers' blanket NOT RUN statements are superseded by these findings for the exercised paths.

## Confirmed software faults and change

- SidecarReply fabricated Garmin -1002 when valid HTTP200 data failed BookWave's schema checks.
  App schema failures now use **-20001** with fixed field identifiers; real transport -1002 is preserved.
  Book-details errors identify their stage and schema field without exposing payload values.
- Error/login views now measure and wrap text inside the round display. Long messages are paged with
  UP/DOWN; BACK returns. Neither changing the schema nor disabling TLS hides an incompatible response.
- Use BookWave phone app opens a persistent foreground setup view with health/login/result guidance.
  Stopping an in-flight setup discards its password and marks it interrupted. Pair Accept rows repeat
  the phone code. Build label **TEST 2026-10-08b** makes installed-code verification possible.
- Companion identifies PHONE state, says Waiting for phone when no stored snapshot exists, and has
  START/Menu help plus BACK guidance. Offline library/audio remains the separate Audio provider.
- Android distinguishes setup availability, missing pairing/capability, account mismatch, rejected
  credentials, content type and schema failures using typed errors and stable reason fields.

No authenticated failing /files response has been captured. A mismatched Sidecar version, malformed
book metadata or real transport content type remains unconfirmed. This change fixes diagnostics and
presentation; it does **not** establish that the owner's download now succeeds.

Read-only deployment evidence: saved Config-MCP Traefik WatchShelf route has a TLS entrypoint and one
upstream. The saved upstream /health returned200 text/plain exact ok; unauthenticated /files returned400
application/json. No credentials/private book data were read, and no production configuration changed.
Public route, authenticated /files, transcode and watch connectivity remain unverified. Octopoda was
available with lexical fallback, found no config:watchshelf keys, and no memory was written.

## Verification and remaining physical checks

Focused native tests cover genuine versus synthetic transport codes, fixed schema-field reporting,
setup error classification and complete measured wrapping including long words/newlines. New tests
compile; focused Run No Evil execution stalled without results. Compilation is not execution evidence.
Both supported production targets for Companion/Audio and both test-enabled binaries compile with
SDK9.2.0/type-check1 and the retained development key. Repository guardrails pass. CI performs
guardrails, four production compiles, two test compiles and two package exports; it does not execute
Run No Evil. Final CI/merge and binary identity accompany delivery in the linked GitHub issues.

| ID | Check | State |
| --- | --- | --- |
| WD01 | Upgrade same filenames/app IDs using retained signer; verify TEST 2026-10-08b in Audio setup and Companion help; retain account/media/progress. | Pending |
| WD02 | Empty provider pairing: compare six digits in Accept row, cancel/resend, then accept; distinguish OS permission prompt. | Pending |
| WD03 | Leave Audio → Use BookWave phone app open. Send setup from Android; watch shows health/login/result and phone shows success or specific failure. | Prior path failed; retest pending |
| WD04 | Wrong password, wrong health destination, provider closed, rejected content type, incompatible JSON and retained account mismatch have distinct readable errors; no credentials persist. | Pending |
| WD05 | Repeat previously failing Download. Record only stage, raw code and fixed field label; then verify actual Wi-Fi transfer/native offline playback separately. | Prior immediate admission failed; retest pending |
| WD06 | Visual: full error and code fit on43/47 target; UP/DOWN reaches every page, BACK works, setup status/help readable. | Prior clipping failed; retest pending |
| WD07 | Companion starts without snapshot: truthful waiting status, START/Menu help, BACK return/exit; phone snapshot transfer/restore remains G-01/G-02. | Prior guidance failed; retest pending |
| WD08 | Stop setup or lock/switch phone profile during health/login; no stale callback saves credentials/account or resets retained books. | Pending |

Record APK/PRG hashes, watch model/firmware, Garmin Connect and Sidecar version. Hardware acceptance,
including the reported freeze's cause, remains open. No watch face or Data Field is introduced.

## Android automated evidence

Final strict `verifyDebug -Pshelfplayer.warningsAsErrors=true` passes (1024 tasks). All 645
app debug tests pass with zero failures/errors/skips. Three new presentation-policy tests and one
correlated setup request integration test cover specific machine-readable failure reasons. Shared
provider fixture adds schema rejection without changing the major-v1 payload contract.

Actual-source reversion proof: replacing SetupUnavailable with the old generic message makes the
setup availability test fail (1 of3); removing sidecar_schema from the repository mapping makes the
correlated integration test fail (1 of1). Both fixes were restored byte-for-byte, then the strict gate
passed. The caller chain remains Settings → ViewModel → repository → correlated provider port; no
UI/network shortcut or credential persistence was introduced. Native [PR16](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/pull/16)
contains the counterpart. Physical WD tests remain pending after these software fixes.
