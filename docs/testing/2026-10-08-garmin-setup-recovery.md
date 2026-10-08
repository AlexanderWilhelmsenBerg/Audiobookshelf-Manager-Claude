# Pairing, phone-driven Sidecar setup and glass settings — 2026-10-08

SET-002, AUTH-001/002/003/005, DL-001/003, BW-SLEEP-01. Architecture & Integration owns
the seam; UI uses existing SettingsCard/GlassCard, theme tokens and native accessible controls.

## Reported physical failures

Owner installed a watch build and reported that pairing offered only Exit, while Android remained
on the sent-code message without a resend action. Entering the Sidecar URL on-watch returned -1002.
These are reported failures, not hardware passes. A watch freeze was also reported; its relationship
to BookWave is unproven and the owner suspects another cause. Preserve that uncertainty.

## Changes and verification

Explicit Accept/Cancel pairing Menu2; retry/cancel/expiry; empty watch can pair before Sidecar login.
Phone enters HTTPS Sidecar URL and uses current username with one-time password; paired provider
health/login owns the existing HTTP contract. Passwords are never durable queued requests or stored
UI state. Setup validates capability, profile/generation/nonce/privacy, and same-account anchors.
Only the opaque Sidecar session persists on the watch. Phone cannot recover its discarded password.

Garmin settings and pop-ups reuse glass tokens. The sleep schedule enable switch directly controls
inline time visibility; off/on preserves start/end and changes no timer or scheduling policy.
Render coverage at 320/375/414/768 dp and Norwegian 320 dp at 200% text is automated; actual phone glass
blur and watch input still require hardware acceptance. Screenshot evidence is fallback raster output.
Hallmark component scope skips macrostructure and keeps the existing theme/font; critique P4 H4 E4
S5 R4 V3 (variety intentionally inherits the established settings system). Native-app relevant gates:
no new hardcoded palette, no fixed text row heights, labelled inputs, bounded/scrollable dialogs,
loading/disabled/error/success feedback and switch semantics. No browser CSS is introduced.

## Physical acceptance still needed

| ID | Procedure | Status |
| --- | --- | --- |
| GS01 | Fresh watch: setup menu stays available, phone code displays Accept/Cancel; each watch button and touch selection works. | NOT RUN after fix |
| GS02 | Cancel by menu/Back, resend twice, wait two minutes, close/reopen provider, interrupt Bluetooth; phone remains retryable and old code cannot bind. | NOT RUN |
| GS03 | Pair without Sidecar login; send HTTPS URL with subpath/trailing slash + prefilled username + password; health/login succeeds and downloads become available. | NOT RUN |
| GS04 | Wrong URL, ABS URL, HTML proxy page/-1002, HTTP URL, wrong password, offline phone/Sidecar; actionable error, retry, no false completion or old-session loss. | NOT RUN |
| GS05 | Dismiss/reopen form and rotate/process-kill/profile switch: password blank; locked/foreign profile/stale nonce cannot send setup or relabel cache. No secrets in logs/storage. | NOT RUN |
| GS06 | Existing paired cache, expired session: same-account reauth preserves books/events; different username/server refuses setup before credential submission. | NOT RUN |
| GS07 | Change the rendered account/watch before Send; during setup lock/switch profile/stop provider; late HTTP response cannot commit to another watch/account. Reopen and retry works. | NOT RUN |
| GS08 | Visual judgment only: glass Garmin card/pop-ups and enabled/disabled sleep menu at normal/200% text, narrow portrait, keyboard and landscape; no clipped fields/actions. | NOT RUN |
| GS09 | Toggle sleep schedule off hides controls; on restores exact times; edit/cancel both times; overnight/equal-time window, pause/resume/manual timer behavior remains unchanged. | NOT RUN |
| GS10 | Watch reboot after success retains session/pair/media, clears pending password/code; offline playback/resume then Force sync. Note any reproduced freeze separately. | NOT RUN |

Existing GD/GF/Companion acceptance remains open. Merge policy permits CI-passed implementation with
these hardware cases pending; do not interpret merge/build as physical acceptance.

## Automated evidence and delivered watch counterpart

The actual-source reversion experiment produced all four expected regression failures for
unconfigured pairing, required setup capability, captured account/watch destination and collapsed
sleep controls. Original source was restored byte-for-byte before final verification. Caller search
confirms Settings routes reach the ViewModel/repository setup and cancel actions and SleepScheduleCard.
The final paired-and-capability predicate is factored into supportsSetup for the existing complexity gate.

Garmin runtime [PR14](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/pull/14) and
guide/evidence [PR15](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/pull/15) are merged.
All nine checks passed for each source head; runtime main
[37754630893](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/actions/runs/37754630893)
and documentation main
[37756099143](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/actions/runs/37756099143)
passed. The retained-key testing ZIP uses runtime456a8a400b0e726233a3ac301700dd0ae40c140a;
main680cd290 differs only in documentation. ZIP SHA256:
`2195512ac40009983a9ccbd6c25d09fa43dc5192bbc1f9789363373574bdee07`.
Both target builds and native tests compile; final simulator execution stalled and is not a pass.

Screenshots are automated test-window fallback raster output with fixture data; they do not establish
physical blur/keyboard/watch acceptance. Normal and 200% form tests verify fields/actions are reachable.

- [glass-settings-320.png](images/2026-10-08-garmin-setup/glass-settings-320.png)
- [glass-settings-375.png](images/2026-10-08-garmin-setup/glass-settings-375.png)
- [glass-settings-414.png](images/2026-10-08-garmin-setup/glass-settings-414.png)
- [glass-settings-768.png](images/2026-10-08-garmin-setup/glass-settings-768.png)
- [glass-settings-nb-320-font2.png](images/2026-10-08-garmin-setup/glass-settings-nb-320-font2.png)
- [garmin-setup-400.png](images/2026-10-08-garmin-setup/garmin-setup-400.png)
- [garmin-setup-320-font2.png](images/2026-10-08-garmin-setup/garmin-setup-320-font2.png)
