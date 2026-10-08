# Watch duration, address and presentation follow-up — 2026-10-08

## Owner findings

Download now immediately reports `-20001`, `Field: files.duration`. This is a real BookWave schema
rejection, rather than evidence of Garmin's content-type error. Companion empty-state text still clips.
The owner requests full information, physical-button cues and a calmer BookWave theme. Android should
add HTTPS, remember Sidecar's address, and reuse the signed-in account without credential entry.

## Implemented and pending

- Numeric duration admission now accepts Number, Long, Float and Double, with positive/range checks.
  Both the admission and diagnostic validators use the same rule. Normal decimal precision is retained.
  Required missing/string/zero/negative/out-of-range values still fail. A fixed reason explains a
  continuing duration rejection without exposing response values. The owner's actual JSON numeric
  representation has not been captured; successful watch transfer is still unverified.
- Companion removes character limits/ellipsis, wraps measured text and exposes every page through
  UP/DOWN. It retains the page during updates for the same profile/book. Null/changed identity resets it.
  Empty state names the phone app and Force sync; Help explains the separate offline Audio provider.
- Shared native drawing uses BookWave dark teal surfaces, semantic colors/font roles, a restrained
  headphone glyph and measured clearance for START/Help and BACK/Exit or Back labels. Page arrows
  align with UP/DOWN. Audio setup/login/error screens reuse the theme. No animation/network polling
  was added to rendering. Build label is **TEST 2026-10-08d**.
- Android supplies HTTPS for bare addresses and saves the successful canonical URL in the existing
  profile/device-owned Room record. Locked-profile UI hides it. No schema or dependency change.
- Provider advertises `reuse_login` for an exact anchored watch account, with no phone password/token.
  Android derives the username from its captured active profile. Fresh watch login is required first.
- Provider advertises additive `setup_session`, accepting only an opaque UUID after matching profile,
  nonce, account anchor, health and authenticated library checks. Old setup/on-watch login still work.
- A tested optional [Sidecar session extension](https://github.com/AlexanderWilhelmsenBerg/bookwave-garmin/blob/main/docs/sidecar-session-extension.md) is prepared. The
  first-time phone access-token exchange is **not implemented/deployed**: automatic approval review rejected
  access-token egress to an unverified user-entered destination. Exact destination and token-sharing
  approval have been requested. No real credential was used and no running Sidecar was changed.
  Current Android uses URL-only retained-watch-session reuse; fresh-watch phone bootstrap is pending.

## Verification

Native compiler/type checking and guardrails validate source and mirrored contracts. Duration tests cover
four numeric classes and malformed values. Presentation tests preserve long metadata; session policy
tests reject an ABS/JWT credential. Test-enabled compilation is separate from test execution: the
Windows Run No Evil attempt stalled without results and was stopped. Do not claim a simulator pass.
Sidecar Node tests run locally and in CI: wire fixtures, bounded/malformed requests, verified principal,
download grant, separate account ownership, idempotence and existing Sidecar refresh-session reuse.
Android focused tests cover bare HTTPS, successful URL persistence, locked-profile hiding and dialog
prefill. The final strict gate and exact CI/build identities are recorded with the delivery.

Hallmark review adapted to native hardware: shared semantic palette, distinct heading/body/progress
roles, no nested cards, restrained accent, concise state/action copy and no decorative motion.
System fonts and native RGB colors are deliberate Connect IQ constraints; web/CSS layout rules do
not override circular-device geometry. Actual pixel appearance remains an owner visual test.

## Physical acceptance still needed

| ID | Test | State |
| --- | --- | --- |
| WL01 | Upgrade matching filenames with retained signer; verify `TEST 2026-10-08d` in Audio setup/Companion Help; retain account, media and progress. | Pending |
| WL02 | Repeat the failing book Download. Admission must reach a real queued job. If rejected, record only raw code, field and fixed reason; then check Wi-Fi transfer and offline playback separately. | Prior `files.duration` admission failed; retest pending |
| WL03 | Visual: empty Companion on 43/47 target; read all text/pages, identify Help and Exit beside their keys, and locate offline Audio using Help. | Prior clipping/guidance failed; retest pending |
| WL04 | Visual: long title, author, chapter, playing/paused/stored state, progress and recency. UP/DOWN reaches all text; no clipping/label overlap; live updates preserve current page. | Pending |
| WL05 | Visual: setup, login and long errors share the theme; all code/field/reason pages are readable; Back returns, Books only acts after configured/non-busy setup. | Pending |
| WL06 | On Android enter host/subpath without scheme; complete existing setup, reopen after process restart/update, see saved address. Explicit HTTP/invalid destinations must fail. Locked/switched profiles must hide foreign address. | Pending |
| WL07 | URL-only phone setup reuses an existing watch login; verify no credential fields, retry, wrong server/account, expired login, lock/switch/disconnect rejection and upgrade. | Implemented; physical retest pending |
| WL09 | Fresh-watch bootstrap from signed-in phone account without watch-entry login. | Blocked on destination/token-sharing approval and extension integration |
| WL08 | With a pre-existing Sidecar refresh session, watch access outlives phone access expiry. A new access-only session requires phone refresh; verify independent Android renewal is preserved. | Blocked on extension deployment/approval |

Keep WD01–08 and earlier GD/GF acceptance open. No watch face, Data Field, iOS or Silo work.

## Software verification record

Garmin duration/layout changes merged in [PR19](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/pull/19).
URL-only retained-watch-login reuse merged in [PR20](https://github.com/AlexanderWilhelmsenBerg/Bookwave-garmin/pull/20)
after all nine checks passed in run37849502942. Merge4b7ee106 matches sourcee4a210a910 tree.
All four local production and two test-enabled targets compile with SDK9.2.0/type-check1.
Seven Sidecar Node tests execute and pass; native simulator test execution remains unverified.
The retained-key watch ZIP has SHA256 ee1039410863c21254f6a5b31c981a03824a0f9c7c143e9e4f63b3e4906fbfea.

Android focused repository, Compose, setup message and URL policy suites pass. Compose renders
the URL-only form at 400 dp and 320 dp/200% text; actions and the field remain reachable by scrolling.
HTTPS default and remembered-address tests failed with their actual fixes removed, then source
was restored byte-for-byte. The fresh-watch test likewise failed with its configured-login guard
removed and was restored. Locked-profile/address ownership and stale-rendered owner/device refusal
are covered. No classpath, database schema or ABS endpoint changed.

No phone or watch physical tests were run for this follow-up. WL01–09 remain explicitly pending
or blocked as listed above; CI/build success does not replace hardware transfer/visual acceptance.
