# Corrected fast-tap phone acceptance — APK2192

2026-10-05, Samsung SM-S928B/API36, native1080×2340/450dpi, Norwegian/dark, font1.0.
Requirement LIB-002/AUTH-002, PD-006, spec17.2/21; U-06 selection/count subcases.

## Identity and gates

- Debug0.10.6.1(2192), package `org.homebord.bookwave.debug`, exact app/workflow source
  `4f2edb36de858b2ea77743b8438c3fb89c82a3aa`; includes browse correction `cf8db633`,
  corrected Author parent and Sign-in, plus #232. It excludes #231's performance experiment.
- [Signed APK/CI run37277738486](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37277738486)
  PASS; artifact11330119961. Artifact/APK SHA256
  `b392207774b508dd7f2336dc08968a63e8d9ebc6ba77c1cd8135d9a50a8aaf1f`;
  signer `c63c72cb2c4b32a8ed3775e4cc0b5754abf06b5beb4481ea5a8f5c5c0dd9217c`;
  Loopbound `7e24529b2a9e419218d2a1423d832b1bf587c8ef`. Digest/certificate/source verified.
- Strict local combined verifyDebug PASS, warnings as errors,3m16s/1,022 tasks.
  Four rendered selection regressions and actual production-source reversion proof pass as recorded
  in the [merge inventory](2026-10-05-pr-merge-test-plan.md).
- In-place install succeeds at07:40UTC. About-screen UI identity is a separate pending check;
  exact APK/source acceptance here comes from validated artifact and successful in-place install.

## Device results

| Subcase | Expected / observed | Result |
| --- | --- | --- |
| U-06-03 fast100ms fling followed immediately by Books tap | Final Books content/caption and sole selected destination agree. Previously FAILED on2189/2191; now first rapid round and five further independent repeats settle correctly. | PASS on2192 |
| U-06-03 cancelled drag, rapid left/right and repeated tabs | Final Books selection and222books caption agree. | Selected sequences PASS |
| U-06-01/02 forward/backward axes and Genres overspill | Books222, Series48, Authors41, Genres44; selected destination matches each caption; final Books restored. | PASS after correcting driver encoding |
| Upgrade retention | All11 table counts and10 non-profile hashes unchanged;55 progress/124History/two downloaded books retain exact hashes. Profile differences are solely lastUsedAt. Settings remain428bytes with original SHA. | PASS for paused upgrade |
| U-06 pill smoothness/manual newer intent | Owner asked to assess normal swipe, reversal and swipe→Books tap on the prepared phone. | Awaiting explicit owner finding |

The generated walk driver initially misread UTF8 Norwegian “Books” as mojibake and failed its
final caption argument while actual Books was selected. The original failure log is retained;
correctly encoded fresh capture/assertion passes. The first profile audit used a nonexistent id
column; the corrected comparison reads the schema's actual primary key and passes. Neither driver
error is counted as an app failure or an accepted test before correction.

Private XML/PNG/DB/log evidence stays in ignored `build/phone-merge-2026-10-05` with source-labelled
corrected captures. No intentional Play/seek/download/sign-out occurred. Font1.0 is restored;
network/orientation/motion settings remain restored. The test-session extended screen timeout,
disposable Sign-in/capture packages and loopback fixture remain for subsequent acceptance tests.

This clears the recorded fast-tap failure in the exercised automatic sequences; actual visual
animation, TalkBack, broader restoration/authorization/live-count states and heard playback remain
separate. [Owner findings](2026-10-05-owner-phone-checks.md) retain APK2191 scope. All five runtime
PRs remain drafts; no merge or issue closure is inferred from these selected results.
