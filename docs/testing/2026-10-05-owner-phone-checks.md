# Owner-observed phone checks — 2026-10-05

This log records the owner's explicit findings for individual visual/acoustic/interaction checks.
The [PR merge test plan](2026-10-05-pr-merge-test-plan.md) retains all remaining obligations.
No unanswered question or automatic screenshot assessment accepts a human check.

Device: Samsung SM-S928B, Android16/API36, Norwegian/dark, native1080×2340 at450dpi.
APK2191: source `6997293ff8f7ccf40abb43d91cc5e042706613b5`, debug0.10.6.1;
[delivery run](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37272646710).
APK SHA256 `6bee65574aa5b50f6a6a69bfd40fef2770660e3d0f1e10cc27f0c9a2d03d6a51`;
signer SHA256 `c63c72cb2c4b32a8ed3775e4cc0b5754abf06b5beb4481ea5a8f5c5c0dd9217c`.
No private media/account names are included. Original private captures are ignored local evidence.

| Case subset | Preparation UTC / configuration | Owner result |
| --- | --- | --- |
| U-08-03/07: completed Author series card | 07:28:25, font1.0; full title/completion, subtle green inward border, no clipped card content | PASS: “Pass — readable, glow visible, no clipping.” |
| U-08-07: same Author at200% text | 07:31:57, font2.0; full Author title/completion/card text readable, no overlap, Back usable | PASS: “Pass — readable and usable at 200%.” |

These are selected visual checks, not completion-policy, TalkBack, all themes/widths or heard-audio
acceptance. No runtime PR is marked ready/merged and no issue is closed from these two checks.
Normal font1.0 is restored and read back before preparing the next check.


U-08-02/07 mixed Author layout on APK2191, prepared07:35:39UTC, font1.0: owner PASS —
“Pass — clear groups, readable, bottom reachable.” This accepts the nine-Series/one-Standalone
visual subset, without accepting coauthor/multi-membership/privacy/TalkBack or other configurations.
The combined fast-tap correction `4f2edb36` passes strict local verifyDebug (3m16s,1,022 tasks).
Its [signed APK workflow](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37277738486)
passed CI; that source is not yet installed or physically accepted by the above APK2191 findings.


## Corrected APK2192 owner gesture result and next test

Exact2192 identity/source/signing evidence is in the [gesture delivery report](2026-10-05-phone-2192-gestures.md).
Owner PASS for U-06-01/03 visual/manual subset: “Pass — smooth pill and correct final highlight.”
This covers normal Books/Series swipes, quick reversal and newer Books tap during settlement.

Heard playback continuity on APK2192 was prepared07:50:20UTC. Owner plays
the current book and navigates Books → Series → Authors → Author detail → Back → Genres → Books
for approximately one minute, then pauses. Expected: same book with no audible gap/restart/skip.
Owner reports PASS: “the test passed, continue.” Speaker/headphone route was not supplied, so this accepts general heard navigation only. This intentionally advances listening progress normally;
the pre-test baseline is captured privately. After-test capture07:53:39UTC shows Genres and Resume (paused); exact route execution was owner-observed, not independently captured.
No two-hour/headset-control/car/offline or whole-playback matrix acceptance is inferred.


Saved progress corroboration07:53:35UTC: exactly one existing progress row advances48,900ms;
no progress row is added/removed. History remains124rows with an updated fingerprint; downloaded
book/file/request hashes and settings remain unchanged. No progress rollback was performed.

Current pending test: U-06-05/U-07 actual TalkBack selected-tab and count speech, APK2192,
prepared07:55:47UTC at Books222. Owner enables installed Samsung TalkBack and assesses all four
tabs/counts. TalkBack was off before this test (other accessibility services are not changed).
Phone automation is paused while the owner enables/uses the screen reader. An unanswered or
skipped check does not accept speech or restore the original TalkBack preference automatically.


## TalkBack completion and owner preference

Owner PASS for U-06-05/U-07 selected-tab and four cached-scope spoken counts on APK2192:
“Talkback works. I will never use talkback, so log it as completed.continue”. This requested check
is complete. Further manual TalkBack procedures are excluded from this session at the owner's
request; unperformed Author/Sign-in-specific speech cases are not falsely recorded as executed.
Automated accessibility semantics and large-text verification continue. TalkBack is restored off,
as before the test, without changing other enabled accessibility services.


## Next owner check: Sign-in predictive Back

Prepared10:33:36UTC on the isolated local test app (`2d567b17`, code2000), not owner APK2192:
Profiles → Add Profile → Address. Cancelled edge Back should stay on Sign-in; completed Back should
return Profiles. Awaiting explicit owner finding; phone automation is paused for this observation.
Automatic success/configuration results are in the [Sign-in phone log](2026-10-05-signin-phone-success.md).


## Sign-in predictive Back owner result

U-03-06 on the isolated source2d567b17/code2000 app: **PASS** — cancelled edge Back stays
on Sign-in; completed Back returns Profiles. Owner: “Pass — cancellation stays, full Back returns
to Profiles”. A later Home capture prevented the driver’s post-gesture Profiles/database check;
that independent confirmation remains unperformed.


## Pending Sign-in large-text observation

The isolated test app is prepared at simulated320dp width (900×1950px at450dpi) and
fontScale2.0 on the pushed Address screen. Awaiting owner title/readability, Back usability
and form/Continue reachability finding; no unanswered question counts as PASS. Display size
and font will be restored to native1080×2340/font1.0 after the observation.


## Sign-in 320dp/200% text owner result

U-03-07 isolated source2d567b17/code2000 Address screen: **PASS** — “Pass — title readable,
Back usable, form reachable”. Native1080×2340 and fontScale1.0 restored and read back. This
accepts the observed Address layout, not every credential/busy/error or language/theme state.


## Additional in-flight toolbar Back — 2026-10-05

U-03-02/03, isolated source2d567b17/code2000, API36/Norwegian/font1.0:
at10:53:45UTC both credential fields are disabled during a12-second loopback login delay.
Toolbar Back returns Profiles at10:53:48; after the delayed response, Profiles remains at10:54:04.
All11 table counts and fingerprints and settings hash match the pre-login snapshot: **PASS**.
Fixture delay is restored to0. No real owner account or library was contacted.

The subsequent Credentials gesture preparation initially sent an unnecessary system Back after
Continue had already closed the keyboard. The driver correctly stopped its credential assertion
on Profiles; removing that redundant action prepares the expected empty Credentials screen.
This setup error supplies no app-failure or predictive-gesture acceptance. Owner Credentials-stage
predictive Back cancellation/completion is pending separately.
