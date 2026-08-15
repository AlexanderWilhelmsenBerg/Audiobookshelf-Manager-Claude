# Open gaps

**As of:** 2026-08-15, end of Phase 4.

Every requirement this app has *not* fully met, and why. Kept as one document rather than a note per phase,
because the question anybody actually asks is "what is missing" and not "what was missing in April".

A gap is listed here only if it is real: a criterion the specification states and this build does not
satisfy. Work that was never in scope for a phase is not a gap, and neither is anything PRODUCT_SPEC itself
defers to a later version.

Each entry says what is missing, what it costs today, and what it depends on. **Blocked** means something
else has to exist first. **Deferred** means the specification itself put it later. **Open** means it could
be built now and has not been.

---

## Phase 1 — Authentication and cached browsing

| Requirement | Gap | State |
| --- | --- | --- |
| AUTH-005 | **Profile PIN / biometric lock.** No lock exists, so `Optional profile PIN or biometric gate` (§3.2) is unbuilt. | Open |

**What it costs today:** anyone holding the unlocked phone can switch profiles and see another household
member's library and progress. On a personal device that is the same exposure as the home screen; on a
shared one it is not.

**What it blocks:** ROUTE-002's *"Auto-play never starts when the active profile is biometric/PIN locked"*
cannot be satisfied, because there is no locked state to check. `OutputDeviceWatcher.onConnected` is where
that check goes.

---

## Phase 2 — Streaming player

| Requirement | Gap | State |
| --- | --- | --- |
| PLAY-003 | **Excluded tracks and the timeline's coordinate space.** A book whose server-side track list excludes a file resolves positions against the wrong offsets. | Open |

**What it costs today:** for the affected books — ones where Audiobookshelf excludes a track from the
audio timeline — a resume can land at the wrong point. Books without excluded tracks, which is nearly all of
them, are unaffected.

**What it needs:** `startOffset` carried into the media item's extras so `PlayerPositions` maps a player
position to a book position using the same offsets `BookMediaSourceFactory` built the concatenation from.
ADR-0016 describes the intended coordinate space; this is a place the implementation does not match it.

---

## Phase 3 — Downloads and offline playback

| Requirement | Gap | State |
| --- | --- | --- |
| DL-003 / §3.3 | **Downloads into a user-chosen folder (SAF).** A volume can be chosen — internal or an SD card — but not an arbitrary directory. | Deferred |
| §12 | **The twelve named job states.** The manifest models four. | Open, by design |
| DL-001 | **Pause and resume a running download from the UI.** Cancel and retry exist; pause does not. | Open |

**SAF** is deferred by PRODUCT_SPEC 3.3 itself, and ADR-0020 records why the volume half shipped without
it: `getExternalFilesDirs` gives real `File` paths that the whole pipeline — `.part`, verify, atomic rename,
sweep — works on unchanged, while a `DocumentFile` tree has no atomic rename, which is the property DL-001's
"atomic commit prevents a false complete state" rests on.

**The job states** are deliberate rather than missed. §12 names twelve states for the *coordinator*; the
manifest stores the four a *file on disk* can be in. Modelling all twelve in the manifest would create two
places that can disagree about whether a file exists, and WorkManager already owns the other eight.

**Pause** is a genuine omission. A download can be cancelled and retried, which resumes from the `.part`, so
the capability exists — there is no button that says *pause*.

---

## Phase 4 — Smart downloader and device automation

| Requirement | Gap | State |
| --- | --- | --- |
| ROUTE-002 | **Auto-play never starts when the profile is locked.** | Blocked on AUTH-005 |
| ROUTE-002 | **`Ask` is `Ready` plus the media notification**, not a separate dismissible prompt. | Open |
| ROUTE-002 | **The global "auto-play when a car connects" switch still exists** and overlaps the Car device's own policy. | Open |
| PLAY-006 | **Advanced buffer values** — explicit minimum, maximum, playback-start, rebuffer-start, target bytes. Only the five presets are offered. | Open |

**`Ask`** currently arms the book, and the paused media session puts a resume control in the notification
shade — which is literally *"show a notification action to resume"*, using the notification the app already
has. What it is not is a prompt that leaves the player untouched until you answer. Both readings are
defensible; the second is more work and nobody has asked for it.

**The car switch** should probably become the Car row's policy and disappear. It was left alone rather than
migrated silently, because a switch that vanishes and reappears somewhere else with a different meaning is
worse than one that overlaps for a while.

**Advanced buffer values** are in PLAY-006's user-facing model. The five presets cover the acceptance
criteria that matter — invalid combinations rejected, applied on next preparation, position survives
recreation — and the advanced form is the part nobody can use without the diagnostics that only just landed.

---

## Phase 5 — Management tools

Not started. Its requirements are listed under EPIC MGR and are not gaps yet; they become gaps if Phase 5 is
declared done without them.

---

## Phase 6 — Android Auto, polish, release

| Requirement | Gap | State |
| --- | --- | --- |
| §3.3 / packaging | **`applicationId` is `com.example.shelfplayer`.** | Open, with a deadline |

Google Play rejects `com.example.`. ADR-0019 records why it did not change with the rename: Android
identifies an install by its `applicationId`, so changing it produces a *second, empty* app rather than a
renamed one — costing a fresh sign-in and every downloaded book. The right moment is the first release,
before anybody has an install to lose, and it needs its own decision about migrating the database.

---

## Things that look like gaps and are not

- **`FakeAudiobookshelfGateway.signIn` returns `AppError.ApiCompatibility`.** Deliberate. The fake exists to
  back repository tests, not to let the app sign in without a server.
- **The capability set is empty on a fresh server.** `AbsCapabilityResolver` records only what a probe
  confirms, and `/status` confirms nothing except the websocket. `RangeDownload` and `ChecksumOrETag` fill in
  after the first download, which is the only honest evidence there is.
- **Changing the download volume moves nothing.** By design — the manifest holds absolute locations, which
  is what makes the setting safe to change.
- **Auto-play from a cold start is unreliable.** Android's, not this app's. The setting says so.
