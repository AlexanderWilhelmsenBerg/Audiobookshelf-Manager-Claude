# Open gaps

**As of:** 2026-08-15, end of Phase 5.

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

**Complete.** All eight slices are done, one of them — source-file deletion — correctly with no feature at
all.

An adversarial audit against every acceptance criterion in EPIC MGR and EPIC USER ran on 2026-08-16 and
found **eight defects that this document did not list**, all of them now fixed. Worth recording what they
were, because they are the shapes this kind of work fails in:

- **Disabling your own account was one mis-tap and unrecoverable** — the account that would undo it is the
  one just disabled. Now refused outright rather than confirmed.
- **The cover cache never invalidated.** A new cover was invisible forever: the loader ignores cache headers
  and nothing evicted, so the URL *was* the key. `?ts=` was documented and not implemented.
- **A save whose follow-up read failed reported as a failed save**, handing back an "unsaved draft" of
  changes already on the server.
- **Permissions were a snapshot** taken when the editor opened, so going offline left every button live.
- **The destructive removal was gated on the grant alone**, not on connectivity.
- **A successful removal said nothing.**
- **A duplicate username was a page-level card**, not a field error.
- **A `REMOVED` scan left a phantom book** on the shelf.

Two were found by the compiler and the linter rather than by the audit, during the fix: an
`?.let { } ?: …` that folded "permitted" into "blocked", and three strings written but never rendered.

A **device run on 2026-08-20** found four more, all fixed, and three of them are the same shape: a feature
that worked perfectly and could not be reached.

- **The account-management row was hidden for a non-admin**, which on a device is indistinguishable from a
  feature that was never built — and was reported as exactly that. The row is now drawn for everybody and
  names the reason when it cannot be used, alongside the account's role and its four server-side grants.
  The gap this closes is diagnostic: until now the only way to find out whether an account held the
  `update` grant was to read the server's own web interface.
- **`theme_mode` and `dynamic_color` had no control.** Both have been in the settings proto since the first
  build and applied by `MainActivity` ever since; nothing ever wrote them, so the only device that could
  have a value was one restored from a build that never existed.
- **There was no language setting** despite a complete `values-nb` translation, so Norwegian was reachable
  only by changing the whole phone's language. See ADR-0022 for why it is carried by the composition rather
  than by `LocaleManager` alone.
- **The About tab described a build from three phases ago**, down to "the management tools are not [built]".

| Requirement | Gap | State |
| --- | --- | --- |
| MGR-006 | **Source-file deletion ships no feature.** Both endpoints exist, and neither can prove the deletion happened: a failed filesystem removal is logged on the server and discarded, and the request succeeds either way. MGR-006 requires the response to confirm it. | **Closed by decision** — ADR-0021 |
| MGR-003 | **A successful match is still uncaptured, and no longer blocking.** Quick match turned out not to be a preview at all, so MGR-003 is built on `GET /api/search/books`, which writes nothing. That endpoint reaches a third party, so its *shape* is captured and its results deliberately are not. | Unblocked |
| MGR-002 | **Cover *upload* has still never been captured** — only removal. It needs a multipart body and an image the capture script should not invent. The contract is known from the project's own source: multipart, part named `cover`, validated on the filename extension. | Open, source-derived |
| MGR-007 | **Embed metadata is not built**, and deliberately: it asks the server to rewrite the user's source audio files, which needs its own decision about whether this app should offer it at all. PRODUCT_SPEC lists it; nothing in Phase 5's plan claimed it. | Open, needs a decision |
| USER-003 | **Deleting a user is not offered**, and disabling is. USER-003 puts deletion in later scope "unless thoroughly contract-tested", and it has not been. Library-access editing is also absent — the requirement asks for a warning about other devices' downloads first. | Deferred, correctly |
| MGR-001 | **A name containing a comma cannot be typed.** Authors, narrators, genres and tags are edited as comma-separated text, which is the right shape for two-item lists and the wrong one for `Smith, Jr.`. A chip editor would fix it and costs four gestures where a text field costs one. | Open, by trade |
| MGR-003 | **The candidate shape is captured from a demo server, not from CI.** Google Books answers `429` to GitHub Actions' addresses on every run, so the CI capture records an empty list. The committed shape comes from a run against `audiobooks.dev` on 2026-08-16 and names an Audible result's keys. The CI fixture will keep disagreeing with it. | Open, environmental |

Two findings are not gaps but standing rules:

- **`GET /api/users` returns every user's live token.** `UserDto` must never model the field, so there is
  nothing to store, log or display. Pinned by `CapturedShapesTest`.
- **Refusals on the management routes are `text/plain`, not JSON** — `Forbidden`, `Not Found` — because
  those handlers use Express's `sendStatus`. The same mechanism is why three of them answered
  `text/plain "OK"` on success. `NetworkErrorMapper` keys on the status code, so this costs nothing today;
  it would cost something the moment somebody reads an error message out of a management response.

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
