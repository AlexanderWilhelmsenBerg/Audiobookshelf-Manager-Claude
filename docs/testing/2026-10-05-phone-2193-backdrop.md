# Signed backdrop candidate APK2193 — 2026-10-05

Requirements LIB-002, SET-002, PRODUCT_SPEC17.3/21; PR #231 visual/performance acceptance.
Control APK2192/source4f2edb36; combined candidate APK2193/sourceba51ab46.

[Checked APK CI37307167906](https://github.com/AlexanderWilhelmsenBerg/Audiobookshelf-Manager-Claude/actions/runs/37307167906)
passes verification, Loopbound packaging and debug assembly. The earlier Standard source verification
passed; its branch-only automatic handoff failed because it required a PR number. The supported direct
Build APK workflow produced this artifact with checks enabled, without bypassing verification.

Artifact digest and APK digest match; APK package, source label and trusted signer are verified.
The Loopbound bundle is pinned to the same commit as2192. Numeric identity is in
[the evidence record](evidence/phone-2193-delivery-2026-10-05.json).

Paused in-place installation PASS at12:27:04UTC on SM-S928B/API36. All11 table counts, all10
non-profile fingerprints and exact428-byte settings are retained. Profile data matches except the
ordinary lastUsedAt timestamp. Progress55, History124, downloaded books/files/requests2/2/2 remain.
No active-playback upgrade, playback-audibility or complete device-matrix claim follows.

The actual saved appearance settings, Norwegian, font1.0/native1080x2340 book list shows222 books
on both control and candidate. Private control/candidate screenshots are preserved locally.
The agent sees coarser card grain in the candidate; owner says the texture looks good but the bottom metadata line is clipped (also present in control).
This is an observation, not visual acceptance or a keep/reject decision. No owner catalogue is published.
The card-text truncation is present in both captures and remains the existing general-row layout gap.

Remaining PERF-06: blur0/6/7/28/48, tint on/off, themes/gradients, moving artwork/parallax edges,
grain and contrast. PERF-07/08: focused/offline/detail/Back paths, audio/progress/queue and remaining
configuration/accessibility coverage. PERF-09: older API fallback, startup/memory and low-memory
hardware if available. Forty earlier alternating benchmark runs show improvement while every CPU P95
still exceeds16.7ms. The quality review and explicit keep/reject decision remain prerequisites.

All five runtime PRs remain draft; no merge or issue closure is recorded by this delivery.
