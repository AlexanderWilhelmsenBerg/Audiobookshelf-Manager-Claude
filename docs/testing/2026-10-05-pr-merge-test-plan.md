# PR phone acceptance and merge requirements — 2026-10-05

Scope: Android runtime PRs #230, #231, #232, #234 and #235. iOS, Silo and Garmin are outside this work.
This is a source-specific acceptance supplement to the canonical [roadmap](../roadmap.md)
and [verification register](roadmap-verification-register.md), not blanket acceptance.
An explicitly requested read-only subagent audited all five PRs and their physical gates.

## Current findings

- Signed APK2189 (`2d567b17`) and APK2191 (`6997293f`) lose a newer Books tap after a 100 ms
  pager fling; an ordinary 350 ms repeat passes. Earlier rapid/mixed-intent PASS rows cover their
  recorded sequences only. The correction is now accepted on signed APK2192 for the recorded fast-tap sequences;
  the original failures remain evidence of the guarded defect.
- #230 now treats every tab tap as a new pager request, including a tap on the currently selected
  axis. The four rendered selection regressions pass. Actual production-source reversion fails
  exactly the new unfinished-fling guard; restoration passes. `ktlintFormat` and strict `verifyDebug`
  pass (2m54s, 1,022 tasks). HomeScreen calls rememberAxisPager and routes HomeAxisBar taps through
  the counter; grep confirms the caller. Requirements: LIB-002, AUTH-002, spec 17.2/21, PD-006.
  The calendar-sensitive History test matcher now distinguishes the timestamp from its day heading;
  History runtime is unchanged. No endpoint, schema, dependency or compatibility-version change.
- APK2189 loses Author scroll on detail return. The hoisted Author list state in #234 passes the
  same original Author → Book → credited Author → Series → Back chain on signed APK2191, with
  the original final row and bounds retained. Remaining restoration paths are unaccepted.
- Selected API36 checks pass: 27 datastore/Keystore tests; all four cached-scope counts in English
  and Norwegian; cached offline Author navigation; selected mixed/standalone/completed Author
  layouts and completion glow. The owner subsequently passed selected tab/count TalkBack and heard browse/Author playback
  on APK2192; other speech/audio routes are not inferred from those findings.
- Both in-place signed upgrades retain 55 progress rows, 124 History rows and two downloaded books,
  with unchanged corresponding table hashes. Only the active profile lastUsedAt changes. Original
  follow-system language/settings are restored. Active-playback upgrade is not accepted.
- Isolated synthetic Sign-in checks on `2d567b17` pass selected root/pushed Back, 320dp/200% text,
  delayed-login cancellation with unchanged tables, and empty password after process death.
  Successful Add/reauth and selected IME/configuration checks subsequently pass on the same
  isolated source; predictive cancellation/completion is owner PASS. The full matrix remains open.
- Four alternating benchmark rounds (40 executions) show candidate CPU P95 improvements of
  8.94% and 6.80%, and overrun P95 improvements of 17.04% and 27.91%. CPU P95 still exceeds
  16.7 ms in every round. A keep/reject decision requires visual quality and regression assessment;
  there is measured benefit, but no claim that the performance budget is met.

Private captures, databases, logs and traces stay in ignored local `build/phone-merge-2026-10-05`.
No media titles, account identity or server address is published here. Results above retain the
original source scope: APK2191 does not contain the newly guarded fast-tap correction.

## Remaining changed-slice physical work

| PR | Work needed before declaring the slice ready |
| --- | --- |
| #230 Browse selection/counts | Corrected fast-tap, selected cancelled/rapid sequences, all axes, owner animation, selected TalkBack tab/count speech and heard browse/Author playback PASS on signed2192. Remaining U-06: other detail-return and recreation/restoration paths. U-07: zero/one/many plurals, search/filter/focused counts, library/profile changes, revoked/hidden items, live Room changes and controlled loading/partial/failed/never-synced states. |
| #231 Backdrop sampling | PERF-06: human control/candidate quality comparison across blur 0, below 7, 7, default 28 and maximum, tint on/off, themes/gradients, artwork/parallax edges, grain and contrast. PERF-07: covers, flat/focused/offline lists, rapid/detail/Back paths and heard audio/progress/queue. PERF-08: English/Norwegian, large text, rotation, reduced motion and TalkBack. PERF-09: API26–30 fallback, API31+ blur, startup/memory and low-memory hardware if available. Decide keep/reject from benefit plus quality; remaining 16.7ms failure stays disclosed. |
| #232 Download cancellation | DC-P02/03: observe cooperative cancellation inside valid final verification and invalid complete-416 verification; prove no rename/delete after cancellation. Controlled 206/If-Range, replacement 200, missing ETag, short/invalid bodies, stale/complete416, second Pause, old/new Worker attempts and terminal failure; compare Book/Downloads/notification bytes. Stop/delete/new-claim races; current/shared/expired/revoked credentials; independent jobs, notification denial, retry/backoff; low-space/write/storage/removable failures; heard offline playback during management; TalkBack/large text/privacy; separate SIGKILL/reboot and long dataSync timeout. Ordinary Pause cannot establish the precise verifier boundary. |
| #234 Author detail | Coauthor/series-only/multiple memberships/missing identity and ordering. Finished/in-progress/unstarted/unknown and incomplete/failed catalogue completion, filtering, borders-off and spoken labels. Heard playback/queue/progress during navigation. Remaining toolbar/system/predictive return, query/filter/sort/scroll, recreation/cold restore. Slow profile changes, lock/sign-out/revocation/artwork privacy. Further manual TalkBack is excluded per owner preference; dedicated unperformed headings/completion speech is not claimed PASS. Remaining display configurations stay open. |
| #235 Sign-in Back/title | Successful Add/reauth cleanup and subsequent Back, IME-first Back, selected credential font/rotation, process-death password clearing and owner predictive Address cancel/complete PASS on the isolated source. Remaining stage-by-stage background/explicit recreation, controlled offline/error/permission and predictive credential/busy cases; remaining widths/languages/themes/reduced-motion/error/cleartext configurations. 320dp/200% title/form owner finding is pending. Further manual TalkBack is excluded per owner preference. |

## Fixtures and merge sequence

Use disposable accounts/server/media for authorization, catalogue and download fault injection.
Download acceptance needs observable validation-phase hooks plus controlled Range/ETag/200/206/416
responses and sanitized offset logs. Never simulate these failures against the owner's real library.
Prepare API26/31/34 equivalents and a tablet/resizable width; sensor/car/removable hardware is needed
only for the applicable outstanding matrix, not assumed present on this API36 phone.

1. Publish and physically accept #230's corrected fast-tap behavior.
2. Reconcile #231/#232/#234 against final #230, then #235 against final #234; resolve conflicts and
   repeat affected strict checks. Dependency: main → #230 → {#231, #232, #234}; #234 → #235.
3. Verify signed APK source/certificate/digest, install in place and record affected tests against that
   exact source. Run independent automated checks without asking the owner to observe them.
4. Pause for each visual, acoustic or interaction case. Ask the owner to confirm Pass or write findings;
   do not accept a preselected answer or elapsed time as a result. Log their answer with case/build.
5. Update the verification register, roadmap and PR descriptions; assess the diff/review and remaining
   applicability against spec section21. Mark ready and merge only when the applicable gates pass.
   After the final merge initiate CI and an APK build together.

All five PRs were OPEN/DRAFT with green checks and no formal reviews in the subagent snapshot.
Those checks precede this newest source correction. No PR is merged or issue closed by this report.
Broad headset/car/two-hour/sensor/release cases remain tracked; they are not automatically asserted
as a new blocker for every unrelated slice. Explicit slice obligations must not be silently waived.


## Corrected signed phone continuation

APK2192 (`4f2edb36`) now passes five100ms fling→newer Books tap repeats, selected cancelled/rapid
sequences and the all-axis walk. Its in-place upgrade retains progress/History/downloads/settings.
See [exact signed evidence and driver corrections](2026-10-05-phone-2192-gestures.md).
The owner animation observation passes; remaining physical obligations above are unchanged.


## 2026-10-05 physical continuation

Selected successful Add/reauth, post-success Back, keyboard-first Back and credential drafts through
font1.3/rotation now PASS on the isolated `2d567b17` artifact. Its Sign-in runtime matches signed
owner2192; other runtime acceptance is not transferred. See the [exact phone success log](2026-10-05-signin-phone-success.md)
for preserved setup failures, remaining matrix and owner predictive-Back PASS.
