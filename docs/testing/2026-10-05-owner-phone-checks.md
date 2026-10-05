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

Current pending test is heard playback continuity on APK2192, prepared07:50:20UTC. Owner plays
the current book and navigates Books → Series → Authors → Author detail → Back → Genres → Books
for approximately one minute, then pauses. Expected: same book with no audible gap/restart/skip.
Speaker/headphone result must be explicit. This intentionally advances listening progress normally;
the pre-test baseline is captured privately. Phone automation is paused until the finding arrives.
No two-hour/headset-control/car/offline or whole-playback matrix acceptance is inferred.
