# What is worth taking from Absorb

**Read 2026-08-29** at [`pounat/absorb`](https://github.com/pounat/absorb), commit `df4dc6e`. Another
Audiobookshelf client — Flutter, Android and iOS, published on Play and the App Store.

## The licensing line, first, because it decides how this document may be used

**Absorb is GPL-3.0.** CLAUDE.md's constraint is *"no official branding or copied GPL code without a
recorded licensing decision"*, and no such decision exists. So:

- **Ideas, yes.** A feature list is not copyrightable, and *"an audiobook app should be able to continue
  into the next book of a series"* is an idea.
- **Code, no. Assets, no. Wording, no.** Not a Dart file, not a translated Kotlin port of one, not a
  screenshot, not a string. Nothing in this document was derived by reading their implementation, and
  nothing built from it may be.

If that ever needs to change, it is an ADR and an owner decision, not a commit.

## The comparison

Absorb's README lists 26 features. Roughly two thirds are things BookWave already has, often under a
different name — offline playback, multi-account, sleep timer with shake-to-reset, per-book speed,
auto-rewind, bookmarks, chapter navigation, search and filtering, Android Auto, real-time sync, listening
history, localisation. Those are not interesting here.

What follows is only what BookWave **lacks**, split by whether it is worth having.

---

## 1. Already agreed, and now built

**Auto-play next in a series.** Absorb ships it; PRODUCT_SPEC 6.4 step 6 asks for it (*"the app queues the
next book"*); BookWave did not have it. The owner asked for it in the same breath as pointing at Absorb, and
it is now built — on by default, skipping finished books, with a switch and a hint that names the sleep
timer as the answer for people who fall asleep.

Worth noting what the comparison did **not** decide: the shape. Absorb's is one line in a feature list. The
decisions that mattered — what happens to a book already finished, whether the setting's default is on,
where the "next book" rule lives so the smart downloader and the player cannot disagree — came from this
codebase, not from theirs.

---

## 2. Worth building, in rough order of value

### Listening statistics (spec §104, §940 — "a later read-only feature")

Absorb has it, the spec already contemplates it, and Audiobookshelf's server keeps the data. BookWave
already reads `GET /api/me/listening-sessions` for the history pane, which is most of the input.

**Why it is worth it:** it is the one feature on this list with no risk attached. Read-only, no new
permission, no new endpoint category, no way for it to lose progress or interrupt playback. A "this month"
figure and a streak is a small screen over data already synced.

**Recommendation: yes, and it is the cheapest thing on this page.**

### Car mode — a large-button driving UI without Android Auto

Absorb ships one. BookWave's answer to a car is Android Auto, which assumes a head unit.

**Why it is worth it:** a phone in a cradle in a car without Android Auto is a real situation, and the
current player card is a phone UI. This is also the one idea here that plays directly to work already done —
the output chooser, the skip intervals and the sleep timer all exist and would simply be bigger.

**Recommendation: yes, but after the device-test backlog clears.** It is a screen, not a subsystem.

### Explicit backup and restore of settings

Absorb exports to a file and imports on another device. BookWave sets `allowBackup="false"`, and R-20
records that as deliberate and reaffirmed by the owner — *"a device-to-device transfer carries nothing"*.

**These are not the same thing.** R-20 is about Android's automatic cloud backup carrying a **token**
somewhere the user did not choose. An explicit export the user asks for, writes where they choose, and
imports themselves is a different trust model.

**Recommendation: yes to settings, and the token stays out regardless.** R-20's last line already says the
token exclusion is *"a separate and standing decision"*. An export containing the server URL, the profile
list, the per-device policies and the playback preferences — and no credential — would honour both.

**Built, 2026-08-30.** Settings → About → *Settings file*, and the import half again on the sign-in screen
so a fresh install can use it before it has an account. It carries exactly what the paragraph above
proposed and nothing else; `SettingsTransferDriftTest` fails if a setting is added to the store without a
decision about which side of that line it belongs on. See `docs/handover.md`, "Moving your settings between
installs", for what travels and what deliberately stays behind.

One thing the owner asked for that this does **not** do: find the file by itself at startup. That needs a
storage permission over every document on the device, asked for so the app could read one — and an
app-private copy would be deleted by the very uninstall the feature exists for. The browse button is the
whole of what is possible without that permission.

### OIDC / SSO sign-in

Absorb supports it beside standard auth. Audiobookshelf supports it server-side.

**Why it is worth it:** for a self-hosted server behind an identity provider it is the difference between
usable and not. AUTH-001's existing flow does not cover it.

**Recommendation: yes if the owner's server uses it, no otherwise.** This is a "does the owner need it"
question rather than a product one, and it is a substantial piece of work — a browser-based flow, a token
lifecycle unlike the current one, and the origin-binding work from the P0 pass to re-examine.

### Custom HTTP headers for reverse-proxy setups

One text field per server, sent on every request.

**Why it is worth it:** self-hosted servers sit behind Cloudflare Access, Authelia and similar, all of which
want a header. Without it those users cannot sign in at all.

**Recommendation: yes, small, and check with the owner whether their setup needs it.** The care needed is in
the logging: a header value is a credential, so 14.5 applies and it must never reach the event log.

---

## 3. Deliberately declined

### Audible ratings, Audnexus metadata, "find missing future books"

Absorb enriches books from Audible's catalogue and Audnexus.

**No, and this is the clearest no on the page.** Product priority 7 is *"keep private self-hosted data out
of logs and reports"*, and the whole point of this app is that a private library stays private. Sending
titles, ASINs or series names to a third party to look up a cover or a rating is exactly the boundary that
priority exists to hold — and it would do it silently, on a library somebody deliberately self-hosts.

If it is ever wanted it is an opt-in with a plain-language explanation of what leaves the device, an ADR,
and a privacy-policy change. Not a feature toggle.

### Chromecast, podcasts, widgets, e-book reading, server backup administration

All five are on PRODUCT_SPEC §3.3's own *"later versions"* list. The spec has already decided; the fact that
another client ships them is not new information and does not reopen it.

### Equalizer / audio DSP

§3.3 again: *"optional local audio DSP features"*. Also the wrong shape for this app's constraints — it means
an `AudioProcessor` in the ExoPlayer chain, which is a per-book performance and battery cost on the one path
that must never stutter (product priority 1).

### The card-based player

Absorb's headline design idea: full-screen "Absorbing" cards instead of a player screen. It looks good in
their screenshots.

**No, on grounds of cost rather than taste.** The player card is the most-tested surface in this app —
accessibility assertions at doubled font scale, adaptive layouts for tablets and foldables, the output
chooser, the sleep timer, chapters, history. Replacing it is a rewrite of the tested thing to change how it
looks, and the owner has not said it looks wrong.

---

## 4. What Absorb does not have that BookWave does

Worth recording, so this document is a comparison rather than a wish list:

- **A per-profile item-visibility model** (P1-01) and privileged operations gated on the server's own
  permissions.
- **A recorded risk register and ADRs** — 28 decisions with their costs written down.
- **A contract-capture harness** (PRODUCT_SPEC 22.4): the app refuses to guess undocumented server
  behaviour, and captured fixtures are what enforce it.
- **A profile lock** with a Keystore-backed verifier and an honest description of what it is not (ADR-0023).
- **An accessibility net that fails the build** — which caught a 10dp touch target on the very links added
  alongside this document.

## Summary

| Idea | Verdict |
| --- | --- |
| Auto-play next in series | **Built** (6.4 step 6) |
| Listening statistics | Yes — cheapest, no risk |
| Car mode | Yes — after the device-test backlog |
| Explicit settings export/import | **Built 2026-08-30** — settings only, never the token |
| OIDC / SSO | Yes *if the owner's server needs it* |
| Custom HTTP headers | Yes — small; keep values out of logs |
| Audible / Audnexus enrichment | **No** — product priority 7 |
| Chromecast, podcasts, widgets, e-books, server backups | No — PRODUCT_SPEC §3.3 already decided |
| Equalizer / DSP | No — §3.3, and wrong shape for priority 1 |
| Card-based player | No — rewrites the most-tested surface for a look |
