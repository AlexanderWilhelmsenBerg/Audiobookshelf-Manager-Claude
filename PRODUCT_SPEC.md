# ShelfPlayer for Android
## Product Requirements and Technical Specification

**Document status:** Build-ready baseline  
**Date:** 2026-08-04  
**Working title:** ShelfPlayer  
**Product type:** Unofficial native Android client for an Audiobookshelf server  
**Primary clients for this specification:** OpenAI Codex and Claude Code  
**Target platform:** Android phones, tablets, foldables, Android Auto, Bluetooth and wired media controls

> The working title must not imply that the application is an official Audiobookshelf product. Do not use the Audiobookshelf logo or official branding without explicit permission.

---

# 1. Executive summary

ShelfPlayer is a native Android audiobook player and server-management client for Audiobookshelf. It must provide dependable streaming, offline downloads, progress synchronization, Android media integration, fast profile switching, and permission-aware administrative tools.

The product has two equally important modes:

1. **Listener mode**
   - Browse libraries.
   - Stream or play downloaded audiobooks.
   - Resume accurately across app restarts and devices.
   - Use lock-screen, notification, Bluetooth headset, wired headset, and Android Auto controls.
   - Configure streaming, download, sleep timer, speed, skip, and automatic playback behavior.

2. **Manager mode**
   - Edit audiobook metadata and covers.
   - Match books against server metadata providers.
   - trigger item or library scans when permitted.
   - Remove an item from the Audiobookshelf database when permitted.
   - Perform true source-file deletion only when the server exposes and confirms a safe dedicated capability.
   - Create and manage users when the logged-in account is authorized.

The application must be **offline-first**. Room is the application’s canonical read source. Network responses update the local database, and the UI observes local state. Playback must continue without the activity or UI process being foregrounded.

---

# 2. Product principles

1. **Playback is sacred.** Management refreshes, websocket reconnects, metadata edits, and UI navigation must never interrupt active playback.
2. **Never lose progress.** Playback position is journaled locally and synchronized opportunistically.
3. **Offline means genuinely offline.** Downloaded books include audio, cover, chapter data, series order, metadata, and the information needed to resume and sync later.
4. **Permissions are enforced twice.** Hide or disable unauthorized actions in the UI, and also enforce permission checks in the domain/data layer.
5. **Destructive actions say exactly what they destroy.** “Remove database entry” and “Delete source files” are separate operations.
6. **No silent cellular surprises.** Streaming and downloads must obey user-configured network policies.
7. **One code path per behavior.** Local and streamed playback use the same queue, progress, chapter, speed, sleep timer, and media-session logic.
8. **Server API volatility is contained.** Audiobookshelf calls live behind a versioned adapter and capability layer.
9. **AI-friendly repository.** Requirements, conventions, commands, module ownership, and Definition of Done are explicit.
10. **Accessible by default.** TalkBack, large text, touch targets, contrast, and adaptive layouts are release requirements.

---

# 3. Scope

## 3.1 Version 1 must include

- Multiple saved server/account profiles.
- Fast user switching without typing credentials again.
- Secure token storage.
- Library browsing, searching, filtering, sorting, author view, series view, and book details.
- Streaming over Wi-Fi or cellular according to settings.
- Configurable streaming buffer presets.
- Manual audiobook downloads for offline use.
- Resumable and integrity-checked downloads.
- Smart download of the next book in a series on unmetered networks.
- Local playback of downloaded books.
- Progress and listening-session synchronization.
- Chapters, bookmarks, playback speed, configurable skip intervals, and sleep timer.
- Android Media3 playback service, lock-screen controls, notification controls, media-button handling, audio focus, and noisy-output handling.
- Playback resumption after process death where Android permits it.
- Per-device Bluetooth/wired automatic-play policy.
- Permission-aware metadata editing, cover editing, matching, scan actions, and item removal.
- Admin/root user creation.
- Download management and storage controls.
- Diagnostics with redacted logs.
- English and Norwegian-ready localization structure. English may be the first complete locale.

## 3.2 Version 1 should include

- Android Auto browsable library and playback.
- Auto-rewind after a pause.
- End-of-chapter sleep timer.
- Shake or notification action to extend the sleep timer.
- Download retention rules and automatic cleanup.
- Optional profile PIN or biometric gate.
- Collections and playlists if the connected server supports them consistently.
- Websocket-driven refresh with REST fallback.
- QR-code onboarding for server URL and optional username, never password or reusable token.

## 3.3 Later versions

- Chromecast or Google Cast.
- Podcast management and playback.
- E-book reader.
- Bulk metadata editing.
- Audio-file reordering and chapter editor.
- Uploading new media files.
- Custom external download folders through Android’s Storage Access Framework.
- Widgets.
- Wear OS companion.
- Server backup administration.
- Advanced administrator dashboards and listening statistics.
- Cross-server library aggregation.
- Optional local audio DSP features.

## 3.4 Non-goals for version 1

- Hosting or replacing an Audiobookshelf server.
- Direct SMB/NFS/filesystem access to server media.
- Editing server files through guessed filesystem paths.
- Copying code from the official GPL application unless this project deliberately adopts a compatible license and records the provenance.
- Supporting arbitrary media servers.
- Full parity with the Audiobookshelf web administration interface.
- Background Bluetooth scanning for nearby devices.
- Circumventing Android background-execution or media-playback restrictions.
- Persisting user passwords after login.

---

# 4. Supported environment

- **Language:** Kotlin.
- **UI:** Jetpack Compose and Material 3.
- **Minimum Android:** API 26 (Android 8.0).
- **Compile/target Android:** API 36 (Android 16).
- **Java bytecode target:** 17.
- **Form factors:** phones, tablets, foldables, resizable windows, Android Auto.
- **Server:** Audiobookshelf, with a tested baseline selected in the compatibility matrix.
- **Transport:** HTTPS strongly recommended. Cleartext HTTP is disabled in release builds by default.
- **Reverse proxy:** Must support the server’s websocket path when real-time updates are enabled.
- **Orientation:** Adaptive; do not lock orientation.

The repository must not depend on globally installed Gradle. It uses the Gradle Wrapper.

---

# 5. Roles and permission model

The app must derive capabilities from the authenticated user object and from server capability probes.

## 5.1 Local role concepts

- **Listener**
  - Browse accessible libraries and tags.
  - Stream and play.
  - Download only if `download` is allowed.
  - Manage own progress and bookmarks.

- **Editor**
  - All Listener actions.
  - Edit metadata, tags, covers, and chapters only when the server grants update permission.

- **Manager**
  - All Editor actions.
  - Remove items only when delete permission is granted.
  - Trigger item/library scans only when the server role and endpoint allow it.

- **Admin/Root**
  - All Manager actions.
  - Create and manage users.
  - Access server-wide management actions exposed by supported APIs.

These are UI concepts, not invented server roles. The actual decision is always made from server-returned permissions and endpoint capability.

## 5.2 Permission requirements

- Every privileged domain operation takes an explicit `ProfileId`.
- The repository loads current permissions immediately before a destructive request when the cached permission state is older than five minutes.
- A `403` response invalidates the permission cache and refreshes the current user.
- Hidden actions must not be reachable through deep links.
- Disabled actions must explain which permission is missing.
- A user switch must cancel privileged edit drafts and queues belonging to the previous user.

---

# 6. Core user journeys

## 6.1 First launch and login

1. User opens the app.
2. User enters a server URL.
3. App normalizes the URL and checks server reachability.
4. App displays the detected server version and connection security.
5. User enters username and password.
6. App authenticates.
7. App stores the returned token encrypted, not the password.
8. App performs a capability probe and initial synchronization.
9. User selects a default library or accepts the server default.
10. Home opens with continue-listening and downloaded content.

## 6.2 Normal playback

1. User selects a book.
2. App chooses a local file if a complete valid download exists.
3. Otherwise, app checks network policy.
4. App starts a server playback session and builds the Media3 queue.
5. Playback begins from synchronized progress.
6. Local progress is journaled during playback.
7. Server progress is synchronized periodically and on important events.
8. When paused or stopped, final progress is flushed.

## 6.3 Offline playback

1. User has previously downloaded a book.
2. Network is absent.
3. Book and all playback metadata remain browsable.
4. Playback starts locally.
5. Progress and listening time are written to the local outbox.
6. On reconnection, local sessions sync idempotently.
7. Conflicts are resolved without assuming that the furthest position is always correct.

## 6.4 Smart next-book download

1. Smart download is enabled for the active profile.
2. The currently playing book is completely downloaded.
3. It belongs to an ordered series.
4. The next accessible book is not downloaded or queued.
5. Device is on an allowed unmetered network and storage/battery constraints are met.
6. The app queues the next book.
7. User receives a low-priority progress notification.
8. The completed book is available offline with metadata and cover.

## 6.5 User switching

1. User opens the profile switcher.
2. Active progress is flushed locally and, when possible, remotely.
3. Playback pauses by default.
4. Profile context changes atomically.
5. Libraries, permissions, downloads, and continue-listening update.
6. The new profile’s last player state is restored paused.
7. The user may press play to resume that profile’s last book.
8. Optional “continue playing across profile switch” is not supported in version 1.

---

# 7. Functional requirements and acceptance criteria

The identifiers below are stable. Code, tests, pull requests, and issues should reference them.

## EPIC AUTH — Server connections and profiles

### AUTH-001 Add server profile

**Requirement:** The user can add a server using a URL, username, and password.

**Acceptance criteria**
- Given a URL without a scheme, when the user submits it, then the app proposes HTTPS first.
- Given a URL with trailing slashes, when saved, then it is normalized without changing a required subpath.
- Given a valid login, then the password is discarded after a token is stored.
- Given an invalid login, then no credential material is persisted.
- Given a self-signed or invalid TLS certificate, then the default build rejects it and presents a clear certificate error.
- “Trust all certificates” must not exist.
- A future custom-CA feature must use an explicit certificate import and show the certificate fingerprint.

### AUTH-002 Multiple profiles

**Requirement:** The user can save multiple account profiles, including multiple users on the same server.

**Acceptance criteria**
- Each profile has a stable local ID independent of username.
- Tokens, permissions, library selection, playback state, settings overrides, and downloads are namespaced by profile.
- Removing one profile does not remove another profile’s data.
- The profile switcher shows server name, username, role, and optional avatar/color.
- The active profile persists across app restart.
- Switching profile takes no more than 500 ms for locally cached screens under normal device load.

### AUTH-003 Secure token storage

**Acceptance criteria**
- Tokens are encrypted using a key protected by Android Keystore.
- Tokens never appear in logs, crash reports, analytics, screenshots generated by diagnostics, database exports, or query strings created by app code.
- Android Auto and media metadata never expose tokens.
- Backup/restore must not create an undecryptable state that crashes the app; it should require reauthentication.
- Passwords are never stored by default.
- Optional biometric locking protects profile selection, not server authentication semantics.

### AUTH-004 Session expiry

**Acceptance criteria**
- A `401` pauses new network actions and marks the profile as requiring reauthentication.
- Existing downloaded playback continues.
- Active streamed playback may continue only while already-authorized media requests remain valid.
- The app never loops login requests.
- The user can reauthenticate without losing downloads, local progress, or preferences.

---

## EPIC LIB — Library browsing

### LIB-001 Initial synchronization

**Acceptance criteria**
- The UI reads from Room, not directly from network DTOs.
- Initial sync stores accessible libraries, items, authors, series, covers, progress, and permissions.
- The home screen can render partial cached content while sync continues.
- Failed optional sections do not fail the whole sync.
- Sync status is visible but non-blocking.
- Pull-to-refresh refreshes the active library.
- Websocket events update Room; REST refresh is used when websocket is unavailable.

### LIB-002 Browse and search

**Acceptance criteria**
- User can browse by library, recently added, continue listening, downloaded, author, series, genre, and collection when supported.
- Search matches title, subtitle, author, narrator, series, ISBN, ASIN, and tags when data exists.
- Search debounce is 300 ms.
- Local cached results appear immediately; server search may enrich results.
- Filters and sort order persist per profile and library.
- Empty, loading, error, and offline states are distinct.

### LIB-003 Series ordering

**Acceptance criteria**
- Numeric and decimal sequence values sort numerically, not lexicographically.
- Non-numeric sequence values sort after numeric values and remain stable.
- When a book belongs to multiple series, the UI shows each membership.
- The smart downloader uses a selected primary series; when none is selected, it uses the first server-provided ordered series and records that choice.
- Series order and next-book information remain available offline for downloaded books.

### LIB-004 Book details

**Acceptance criteria**
- Details show title, subtitle, author, narrator, series and sequence, duration, progress, description, genres, tags, publication data, language, file count, download size, and download state when available.
- HTML descriptions are sanitized before rendering.
- The main play button displays `Play`, `Resume`, or `Restart` appropriately.
- Local and remote availability are independently visible.
- Privileged actions appear only when allowed.

---

## EPIC PLAY — Playback

### PLAY-001 Native background playback

**Acceptance criteria**
- Playback runs in a `MediaLibraryService`.
- Closing the activity does not stop playback.
- Lock screen, notification, Bluetooth, wired headset, Android Auto, and system media controls can play, pause, seek, skip, and stop as supported.
- Media notification shows cover, title, author, progress, play/pause, backward, and forward controls.
- The service declares the media-playback foreground-service type and required permissions.
- Only one local audio media session exists.
- A process restart restores the last playable item in a paused state unless a valid user action requests playback.

### PLAY-002 Audio focus and route handling

**Acceptance criteria**
- The player requests audio focus through Media3.
- A transient focus loss pauses or ducks according to setting; default is pause.
- A permanent focus loss pauses.
- Incoming calls and navigation prompts behave according to Android audio focus.
- Disconnecting headphones while playing pauses immediately.
- Playback never unexpectedly moves from headphones to the phone speaker.
- Reconnecting a route follows the configured per-device policy.

### PLAY-003 Playback queue and tracks

**Acceptance criteria**
- Multi-file audiobooks play in server-defined track order.
- Seeking across track boundaries preserves global book position.
- Excluded server tracks are not played.
- Chapter navigation works independently of file boundaries.
- A missing local part marks the download incomplete and prevents false “downloaded” state.
- If a local file becomes unreadable, playback stops safely and offers repair/redownload.
- No automatic fallback to cellular streaming occurs unless policy allows it.

### PLAY-004 Progress persistence

**Acceptance criteria**
- Local progress is journaled at least every 5 seconds during active playback.
- Remote progress is synchronized approximately every 30 seconds, plus on pause, seek completion, chapter change, book change, sleep-timer stop, service shutdown callback, and app background transition when possible.
- The current position must survive process death with no more than 10 seconds lost under normal conditions.
- A finished threshold defaults to 95% and is configurable from 90–99%.
- Marking finished is explicit when the duration or server data is unreliable.
- Rewinding intentionally is preserved; conflict resolution never blindly chooses the maximum position.

### PLAY-005 Offline session synchronization

**Acceptance criteria**
- Every offline listening session has a UUIDv4 identifier.
- Retrying a session sync is idempotent.
- Synced outbox records are retained for seven days for diagnostics, then compacted.
- If two devices update the same book, the event with the latest trustworthy server timestamp wins unless the local device has a newer unsynced event.
- Clock-skew greater than five minutes is detected and shown in diagnostics.
- A sync conflict never deletes local playback history silently.

### PLAY-006 Streaming buffer

**Requirement:** The user can configure streaming buffer size.

**User-facing model**
- Automatic.
- Low: 15 seconds minimum / 30 seconds maximum.
- Standard: 30 seconds minimum / 60 seconds maximum.
- High: 60 seconds minimum / 180 seconds maximum.
- Very high: 120 seconds minimum / 300 seconds maximum.
- Advanced: explicit minimum, maximum, playback-start, rebuffer-start, and optional target bytes.

**Acceptance criteria**
- Buffer settings affect remote streams only; local playback uses Media3 local defaults.
- Invalid combinations are rejected: minimum must not exceed maximum; start thresholds must not exceed minimum.
- Changing a preset is applied on the next player preparation or controlled player recreation.
- Active playback position and queue survive player recreation.
- The app displays an estimate that larger buffers use more memory and data ahead of playback.
- The default is Automatic.
- Rebuffer count and startup latency are available in local diagnostics without media titles unless user opts in.

### PLAY-007 Speed and skip controls

**Acceptance criteria**
- Speed range is 0.5× to 3.0× in 0.05 increments.
- Per-book speed overrides profile default.
- Skip-back and skip-forward values are independently configurable from 5–120 seconds.
- Defaults are 15 seconds back and 30 seconds forward.
- Hardware/media controls use the configured values where the platform command supports it.
- Pitch is preserved.
- Speed persists across local and streamed versions of the same item.

### PLAY-008 Sleep timer

**Acceptance criteria**
- Timer options: 5, 10, 15, 30, 45, 60, 90 minutes; end of chapter; custom.
- Optional fade-out occurs over 5–30 seconds.
- Timer survives activity recreation and displays remaining time in notification and player.
- A notification action extends the timer by the configured amount.
- Optional shake-to-extend requires explicit opt-in and must not run motion sensing continuously when no timer is active.
- Timer expiration pauses, records progress, and syncs.
- End-of-chapter handles malformed or absent chapters gracefully.

### PLAY-009 Auto-rewind after pause

**Acceptance criteria**
- Disabled by default.
- User configures rewind buckets, for example:
  - pause under 2 minutes: 0 seconds;
  - 2–10 minutes: 5 seconds;
  - 10–60 minutes: 15 seconds;
  - over 60 minutes: 30 seconds.
- Rewind cannot move before chapter/book start.
- Rewind is not applied after a user seek or when resuming immediately after audio-focus interruption unless configured.
- Applied rewind is visible briefly and can be undone.

---

## EPIC ROUTE — Headsets, Bluetooth, and automatic start

### ROUTE-001 Media-button resume

**Acceptance criteria**
- When the user presses Play on a connected headset, Android routes the command to the media session and the last item for the active profile resumes when platform policy permits.
- If no playable item exists, the command does nothing and logs a non-fatal diagnostic.
- A media-button command is treated as explicit user intent and may start playback.
- Playback resumption metadata is published for Android system media controls.

### ROUTE-002 Per-device playback policy

Each known output device has one of these policies:

- **Never react**
- **Arm only:** load last book paused; headset Play starts it
- **Auto-play:** automatically resume on connection
- **Ask:** show a notification action to resume

**Acceptance criteria**
- Default policy for every new device is `Arm only`.
- `Auto-play` requires explicit user selection for that device.
- The app never enables Auto-play globally without a warning.
- Bluetooth device selection requires only the minimum Nearby Devices permission needed.
- Wired headset is represented as a device category because a stable device identity may be unavailable.
- Car audio and hearing aids can have separate policies.
- Device display name, type, and last-seen date are stored; hardware addresses are not shown in ordinary UI.
- Duplicate connection callbacks within ten seconds trigger at most one action.
- Auto-play is best-effort and the UI states that Android/OEM background rules may prevent a cold-start connection trigger.
- Auto-play never starts when the active profile is biometric/PIN locked.
- Auto-play never starts explicit content when the device is classified as a speaker unless separately confirmed.

### ROUTE-003 Startup mode

**Acceptance criteria**
- Profile setting options:
  - Restore last item paused.
  - Restore and play only after explicit media command.
  - Resume automatically when app is opened.
- App launch alone never starts playback by default.
- A Bluetooth connection policy may override the general startup setting only for that selected device.
- After reboot, the app does not start a media foreground service from boot.
- Playback resumption remains available through supported Android media surfaces.

---

## EPIC DL — Downloads and offline storage

### DL-001 Manual download

**Acceptance criteria**
- Download button is visible only when the server grants download permission.
- Before queuing, app checks estimated size and free space.
- User can choose Wi-Fi only or “download now using current network” when cellular downloads are allowed.
- Download continues in background with a progress notification.
- Each track supports resume using HTTP range requests when the server supports them.
- Temporary parts use `.part` naming and are not playable.
- A book becomes `Downloaded` only after all required audio tracks, cover, and offline manifest are committed.
- Atomic commit prevents a crash from creating a false complete state.
- User can pause, resume, cancel, retry, and remove a download.
- Canceling removes temporary files after confirmation or after a cleanup grace period.
- A failed part retries with exponential backoff and jitter.
- Authentication failures pause the job and request reauthentication.

### DL-002 Download integrity

**Acceptance criteria**
- At minimum, validate response status, expected content length when supplied, non-zero file size, and readable media container.
- If the server provides a checksum or ETag, persist and validate it.
- The offline manifest records server ID, profile entitlements, item ID, file IDs/inodes, paths, sizes, MIME types, durations, and completion state.
- On app start, an incremental verifier checks manifests, not every byte.
- A full verification action is available in diagnostics.
- Corrupt files are quarantined or removed only after user-visible confirmation unless they are incomplete temporary parts.

### DL-003 Storage layout

Default app-private layout:

`files/offline/<server-id>/<item-id>/<file-id>.<extension>`

Associated manifest and cover are stored alongside or in Room using stable identifiers.

**Acceptance criteria**
- Filenames from the server are sanitized and never used as untrusted paths.
- Path traversal is impossible.
- Downloads are not exposed to other apps by default.
- A shared physical blob may be referenced by multiple local profiles only when they belong to the same server item and each profile has a recorded entitlement.
- Removing a profile decrements references; physical media is deleted only when no profile references it.
- Logging out does not delete downloads unless chosen.
- Removing a server connection presents choices: keep orphaned local media, export later, or delete.

### DL-004 Network policy

Settings:
- Streaming: Wi-Fi only / Wi-Fi and cellular / Ask on cellular.
- Manual downloads: Wi-Fi only / Wi-Fi and cellular / Ask.
- Smart downloads: unmetered only; no cellular option in version 1.
- Optional “treat selected SSIDs as metered” is later scope because SSID access has privacy implications.

**Acceptance criteria**
- Android network metering state is the source of truth.
- A user override applies only to the current requested action unless they change settings.
- Switching from Wi-Fi to cellular during a disallowed download pauses it.
- Switching during streaming prompts or pauses according to policy.
- The prompt is debounced and does not appear repeatedly for the same session.

### DL-005 Smart next-book downloader

Settings:
- Enabled per profile.
- Number of future books to keep: 1–3; default 1.
- Trigger: current book is fully downloaded; optional later trigger at playback percentage.
- Unmetered network required.
- Battery-not-low required by default.
- Storage-not-low required.
- Charging required optional.
- Minimum free space reserve: default 2 GB, configurable.
- Retention: keep all / remove finished after N days / keep last N finished.
- Series selection behavior for multi-series books.

**Candidate algorithm**
1. Load active profile and current playback item.
2. Verify smart download enabled.
3. Verify current item has a complete valid local download.
4. Resolve the selected primary ordered series.
5. Sort accessible series items using normalized sequence.
6. Find the first later item not downloaded, queued, missing, invalid, or finished-and-retained.
7. Verify user access and download permission.
8. Verify WorkManager constraints.
9. Verify projected size and free-space reserve.
10. Enqueue uniquely by `(profile, server, item)`.
11. Re-evaluate after every completion, cancellation, profile switch, permission refresh, library event, and connectivity change.

**Acceptance criteria**
- The same book is never queued twice.
- A queue survives process death and reboot.
- Smart download never uses a metered connection.
- If sequence is ambiguous, app does not guess silently; it marks the series for user selection.
- If there is no next book, no job is created.
- If the next book is inaccessible to the profile, it is skipped and recorded in diagnostics without exposing inaccessible metadata.
- Manual download has higher priority than smart download.
- User can see why a smart download is waiting.
- User can cancel a smart download without disabling the feature.
- A canceled candidate is suppressed for seven days unless manually re-enabled.

### DL-006 Automatic cleanup

**Acceptance criteria**
- Cleanup never removes the currently playing book.
- Cleanup never removes a book with unsynced progress/session data.
- Cleanup never removes pinned downloads.
- Cleanup respects retention and free-space reserve.
- The user receives a summary after automatic cleanup.
- Deletion is transactionally reflected in Room and filesystem state.
- Failed file deletion is surfaced as repairable storage state.

---

## EPIC SYNC — Synchronization and real-time updates

### SYNC-001 Capability handshake

On login and after server upgrade detection, persist:

- server version;
- authentication mode;
- supported endpoint groups;
- websocket availability;
- user and permission shape;
- playback-session support;
- local-session sync support;
- metadata-update support;
- match/search provider support;
- scan support;
- user-management support;
- source-file-delete support, if any;
- range-download support;
- ETag/checksum support.

**Acceptance criteria**
- Unknown capability is treated as unsupported, not assumed supported.
- Capability probes are read-only unless explicitly documented otherwise.
- The compatibility result is visible in diagnostics.
- Features are disabled with an explanation when unsupported.
- API DTO parsing ignores unknown fields.
- Missing expected fields produce a typed compatibility error rather than a crash.

### SYNC-002 Websocket resilience

**Acceptance criteria**
- REST functionality remains usable when websocket is unavailable.
- Reconnection uses bounded exponential backoff with jitter.
- App lifecycle and network changes trigger appropriate reconnect.
- Duplicate events are idempotent.
- Event handlers update repositories/Room, not Compose state directly.
- Tokens are passed using the supported authentication mechanism and never logged.
- Reverse-proxy websocket errors are diagnosable.

### SYNC-003 Sync scheduling

**Acceptance criteria**
- Foreground refresh is immediate and cancellable.
- Persistent background refresh uses WorkManager.
- Periodic work respects Android minimum intervals and battery policies.
- Work is uniquely named per profile/server to prevent duplicates.
- Profile removal cancels its work.
- Background work never wakes the device solely to refresh cover art.
- Active playback progress synchronization is owned by the playback service, not periodic WorkManager.

---

## EPIC MGR — Metadata and server management

### MGR-001 Edit metadata

Editable fields when supported:
- title;
- subtitle;
- authors;
- narrators;
- series and sequence;
- genres;
- published year/date;
- publisher;
- description;
- ISBN;
- ASIN;
- language;
- explicit flag;
- abridged flag if supported;
- tags.

**Acceptance criteria**
- Editor loads the latest item before save.
- Dirty fields are tracked.
- Validation errors are inline.
- A save request sends only the server-supported shape.
- On success, Room updates immediately and then refreshes from server.
- On conflict or stale data, user sees field-level differences and can reload or overwrite when safe.
- Network failure retains an explicit unsaved draft locally.
- Privileged edits are not queued for blind offline execution in version 1.
- User may discard a draft.
- Rich descriptions are sanitized on display and normalized on edit.

### MGR-002 Cover management

**Acceptance criteria**
- User can select an image using Android Photo Picker.
- App validates MIME type, decode success, dimensions, and configured size limit.
- App can upload a cover or request a server-side cover URL only through supported endpoints.
- Preview is shown before commit.
- Cover cache invalidates after successful update.
- Removing a cover requires confirmation.
- Tokens are not appended to third-party cover URLs.

### MGR-003 Match metadata

**Acceptance criteria**
- Quick match is supported when the server supports it.
- Full candidate search and preview is supported only when compatible provider/search endpoints are available.
- User sees provider, candidate title, author, year, cover, and fields that will change.
- Existing non-empty fields are not overwritten without an explicit choice.
- “Prefer matched metadata” server behavior is displayed if known.
- Match results are treated as untrusted display data and sanitized.
- Match action is permission checked immediately before execution.
- Batch matching is later scope.

### MGR-004 Scan item/library

**Acceptance criteria**
- Item scan appears only for roles/endpoints that allow it.
- Library scan appears only for roles/endpoints that allow it.
- Force rescan requires a second confirmation because it can be expensive.
- The user sees started, running if detectable, completed, and failed states.
- Repeated taps do not start duplicate scans.
- A scan result refreshes affected local entities.
- Scanning never blocks playback.

### MGR-005 Remove item from Audiobookshelf database

**Acceptance criteria**
- The action label is exactly `Remove from Audiobookshelf database`.
- Confirmation states that media files remain on the server and a later scan may re-add the item.
- User must have delete permission.
- Offline invocation is blocked.
- On success, the item is removed from Room only after server confirmation.
- Local download removal is a separate checkbox, unchecked by default.
- Undo is not promised unless the server supports it.

### MGR-006 Delete source files

**Requirement:** True source-file deletion is capability-gated.

**Acceptance criteria**
- The action does not exist unless the connected server reports a dedicated, tested source-file-delete capability.
- Database removal is never presented as file deletion.
- The app never deletes media by using server filesystem paths.
- Confirmation names the book, states that source audio files will be permanently deleted, and requires typing `DELETE` or biometric re-confirmation.
- The server response must explicitly confirm deletion.
- The app triggers or observes a refresh afterward.
- If the server cannot prove deletion, the UI reports uncertain state and does not claim success.
- No bulk source-file deletion in version 1.

### MGR-007 Embed metadata

**Acceptance criteria**
- Available only to authorized admin users when the server supports it.
- UI warns that the server will modify source audio files.
- User selects metadata only, cover only, or both if the API supports it.
- Operation is non-blocking and has visible status.
- The app advises the user to maintain server-side backups.
- A failed operation never marks local metadata as embedded.

---

## EPIC USER — Server user management

### USER-001 List users

**Acceptance criteria**
- Only admin/root profiles can open server user management.
- The screen displays username, type, active/locked state, permissions, accessible libraries, and accessible tags where supported.
- Tokens and password hashes are never displayed.
- User list is not cached for offline viewing by default.

### USER-002 Create user

**Acceptance criteria**
- Required: username, password, role/type, active state.
- Optional: library and tag access, download/update/delete/upload permissions, explicit-content access.
- Username and password validation matches server feedback.
- Password is held only in transient UI state and cleared after submission.
- Duplicate username returns a field-level error.
- Success refreshes the user list.
- The app never auto-logs into the created account.
- Creating root-equivalent accounts requires an additional warning if the server permits it.

### USER-003 Update or disable user

**Acceptance criteria**
- Editing user permissions requires admin/root.
- The app warns before removing library access that could affect that user’s downloads on other devices.
- Disabling is preferred over deletion when available.
- The currently authenticated user cannot accidentally remove their own required admin access without an explicit elevated confirmation.
- Delete-user support is later scope unless thoroughly contract-tested.

---

## EPIC SET — Settings

### SET-001 Settings hierarchy

Settings precedence:
1. Per-book override.
2. Per-device override.
3. Per-profile setting.
4. Global app setting.
5. Product default.

**Acceptance criteria**
- The UI shows when a value is inherited.
- Reset-to-default is available at every override level.
- Settings are stored in Proto DataStore.
- Sensitive values are not stored in ordinary DataStore.
- Settings migration is versioned and tested.

### SET-002 Settings inventory

**Playback**
- default speed;
- skip back/forward;
- audio focus behavior;
- auto-rewind;
- finished threshold;
- sleep timer defaults;
- fade duration;
- shake-to-extend;
- restore behavior.

**Streaming**
- cellular policy;
- buffer preset/advanced values;
- retry behavior;
- prefer local media;
- fallback-to-stream confirmation.

**Downloads**
- manual network policy;
- smart downloads;
- future-book count;
- charging/battery/storage constraints;
- free-space reserve;
- retention;
- simultaneous download count, default 2;
- maximum retry count;
- pin downloads.

**Devices**
- per-device connection behavior;
- car audio;
- hearing aids;
- wired headset;
- device permission status.

**Profiles**
- active profile;
- profile display name/color;
- PIN/biometric lock;
- default library;
- explicit content behavior.

**Appearance/accessibility**
- system/light/dark;
- dynamic color;
- text scaling follows system;
- reduced motion;
- high-contrast option;
- cover-grid density.

**Privacy/diagnostics**
- optional crash reporting;
- include server host in diagnostics, default off;
- include media titles, default off;
- export redacted diagnostic bundle;
- clear caches;
- verify downloads.

---

# 8. Recommended additional features

These features have high audiobook value and should be scheduled before broad server-administration parity:

1. **Auto-rewind after long pauses.**
2. **End-of-chapter sleep timer.**
3. **Timer extension from headset/notification/shake.**
4. **Bookmarks with optional short notes.**
5. **Per-book speed and skip settings.**
6. **Pinned offline books.**
7. **Automatic cleanup of finished books.**
8. **Android Auto browsing and playback.**
9. **A “Why is this waiting?” panel for downloads.**
10. **One-tap repair of corrupt/incomplete downloads.**
11. **Offline series order and next-book visibility.**
12. **Profile PIN/biometric protection, especially for admin accounts.**
13. **Server compatibility diagnostics.**
14. **Playback history with a private/local-only option.**
15. **Explicit-content protection for speaker auto-play.**
16. **Configurable chapter-end chime, disabled by default.**
17. **Undo last seek and undo auto-rewind.**
18. **Listening statistics as a later read-only feature.**

---

# 9. Technical architecture

## 9.1 Architectural style

Use a modular, offline-first, unidirectional-data-flow architecture:

- **UI layer:** Compose screens, state holders, navigation.
- **Domain layer:** use cases and policy engines.
- **Data layer:** repositories, Room, network, filesystem, websocket.
- **Playback layer:** Media3 service and controller bridge.
- **Worker layer:** sync, download coordination, verification, cleanup.
- **Core layer:** models, errors, logging, security, test utilities.

Room is the source of truth for data displayed by the UI. The network layer never returns DTOs directly to Compose.

## 9.2 Suggested Gradle modules

```text
:app
:core:model
:core:common
:core:designsystem
:core:database
:core:datastore
:core:network
:core:security
:core:testing
:data:auth
:data:library
:data:playback
:data:downloads
:data:management
:domain
:playback:service
:feature:onboarding
:feature:home
:feature:library
:feature:book
:feature:player
:feature:downloads
:feature:profiles
:feature:settings
:feature:management
:feature:users
:feature:diagnostics
:auto
```

Start with fewer modules if build complexity becomes counterproductive, but preserve package boundaries. A reasonable first milestone can combine all `feature:*` code in `:app` while keeping core/data/playback modules separate.

## 9.3 Dependency rules

- Feature modules may depend on domain and design system.
- Domain depends only on core model/common.
- Data modules implement domain repository interfaces.
- Playback service depends on playback data/domain and core, never on UI.
- Network DTOs stay inside data/network.
- Room entities stay inside database/data.
- No cyclic module dependencies.
- `:app` performs final dependency injection wiring.

## 9.4 Dependency injection

Use Hilt.

- Constructor injection by default.
- No service locator.
- Qualifiers for authenticated vs unauthenticated clients.
- Profile-scoped objects are keyed explicitly because Hilt does not provide a custom profile lifetime automatically.
- The playback service obtains profile context through a thread-safe repository, not Activity state.

---

# 10. Technology choices

## 10.1 Required stack

- Kotlin.
- Kotlin coroutines and Flow.
- Jetpack Compose Material 3.
- Navigation Compose.
- AndroidX Lifecycle/ViewModel.
- Media3 ExoPlayer.
- Media3 `MediaLibraryService`.
- Media3 OkHttp data source for authenticated streaming.
- Room.
- Proto DataStore.
- WorkManager.
- Hilt.
- Retrofit with OkHttp.
- Kotlinx Serialization.
- Coil for cover images.
- Socket.IO-compatible Android client isolated behind an interface, only if required by the tested server; REST fallback is mandatory.
- Android Photo Picker.
- Android Keystore.
- Timber or a small structured logging facade in debug; release logging must be redacted.

## 10.2 Why native Kotlin

- Best integration with Media3, foreground playback, media buttons, Android Auto, audio focus, WorkManager, Room, DataStore, and per-device routing.
- Lower lifecycle risk than placing the core player behind a web view or cross-platform bridge.
- Clear static types for a server API whose published documentation may lag implementation.
- Better control over long-running downloads and offline storage.

## 10.3 Networking

Use one configured OkHttp stack per server/profile context, with:

- base URL normalization;
- authorization interceptor;
- user agent containing app version but no private identifiers;
- connect/read/write/call timeouts appropriate to endpoint type;
- separate long-lived websocket client;
- redacting HTTP logger enabled only in debug;
- retry policy implemented above OkHttp, not `retryOnConnectionFailure` alone;
- no token query parameters generated by app code unless a specific server endpoint proves headers are impossible;
- certificate validation using platform trust;
- optional future custom CA support.

Retrofit handles JSON and multipart administrative calls. Media3 streaming uses `OkHttpDataSource.Factory` with authorization headers.

## 10.4 Server API adapter

All server calls must go through:

```text
AudiobookshelfGateway
  ├─ AuthApi
  ├─ LibraryApi
  ├─ PlaybackApi
  ├─ ProgressApi
  ├─ DownloadApi
  ├─ ManagementApi
  ├─ UsersApi
  ├─ EventApi
  └─ CapabilityResolver
```

The gateway exposes domain models and typed results, not raw response bodies.

Create compatibility implementations by server family only when necessary:

```text
AbsGatewayLegacy
AbsGatewayJwt
AbsGatewayCurrent
```

Prefer feature probing over brittle version comparisons. Version comparisons may select known workarounds.

---

# 11. Playback implementation

## 11.1 Service

Implement `ShelfPlaybackService : MediaLibraryService`.

Responsibilities:
- own a single ExoPlayer;
- own the MediaLibrarySession;
- publish browsable content to Android Auto/system;
- manage audio focus through Media3;
- convert domain queue entries to MediaItems;
- keep playback independent from UI;
- persist progress;
- handle media buttons;
- handle playback resumption;
- expose custom commands for bookmark, sleep timer, mark finished, and download when supported;
- react to route/noisy events;
- never perform large library syncs.

The activity connects through `MediaBrowser`/`MediaController`.

## 11.2 Player construction

- `DefaultMediaSourceFactory` using a `DefaultDataSource.Factory`.
- Local files handled by standard file/content data source.
- HTTP handled by Media3 OkHttp data source with bearer authorization.
- `DefaultLoadControl` constructed from validated stream-buffer settings.
- Audio attributes set for spoken-word media.
- Handle audio becoming noisy.
- Seek parameters should favor accurate audiobook position.
- Player recreation is coordinated and restores queue, index, position, speed, repeat mode, and play state.

## 11.3 Global audiobook timeline

Represent position as a global duration from book start.

For multi-file books:
- each track has `startOffset`;
- global seek resolves the containing track and local offset;
- player callbacks convert track-local position back to global position;
- chapter model uses global start/end;
- download/local manifests preserve offsets.

## 11.4 Playback source selection

```text
if completeValidLocalDownload:
    use local
else if networkPolicyAllows:
    start remote playback session and stream
else:
    show actionable network-policy state
```

Do not mix local and remote tracks within one book unless a repair flow explicitly supports it. Partial local downloads are not used for normal playback in version 1.

---

# 12. Download implementation

## 12.1 Coordinator

Create a `DownloadCoordinator` with a persistent Room queue.

Each book download is a parent job with child parts:
- cover;
- manifest/metadata;
- each audio file;
- optional ebook later.

States:
- `QUEUED`
- `WAITING_FOR_NETWORK`
- `WAITING_FOR_STORAGE`
- `WAITING_FOR_AUTH`
- `DOWNLOADING`
- `PAUSED`
- `VERIFYING`
- `COMMITTING`
- `COMPLETED`
- `FAILED_RETRYABLE`
- `FAILED_PERMANENT`
- `CANCELED`

## 12.2 Execution

- User-initiated jobs should begin promptly and show foreground progress.
- Smart jobs use WorkManager constraints:
  - `UNMETERED`;
  - battery not low;
  - storage not low;
  - optional charging.
- Limit concurrency to two books and two parts per book by default.
- Use unique work names.
- Persist received byte count and resume metadata.
- If a URL/token expires, reacquire a valid download request rather than restarting unnecessarily.
- Download into app-private temporary storage.
- Verify then atomically rename/commit.

## 12.3 Progress notification

Show:
- book title, unless privacy setting hides it;
- percentage and bytes;
- pause/cancel;
- queue count;
- waiting reason;
- completion/failure action.

Do not spam a notification per file.

---

# 13. Data model

At minimum, Room should contain the following conceptual tables. Exact normalization may vary.

```text
ServerEntity
ProfileEntity
ProfilePermissionEntity
ServerCapabilityEntity
LibraryEntity
AuthorEntity
SeriesEntity
BookEntity
BookAuthorCrossRef
BookSeriesCrossRef
GenreEntity
BookGenreCrossRef
TagEntity
BookTagCrossRef
TrackEntity
ChapterEntity
MediaProgressEntity
BookmarkEntity
PlaybackSessionEntity
ProgressOutboxEntity
DownloadEntity
DownloadPartEntity
OfflineManifestEntity
OfflineEntitlementEntity
KnownOutputDeviceEntity
DevicePlaybackPolicyEntity
MetadataDraftEntity
SyncStateEntity
EventDedupEntity
```

## 13.1 Identity rules

- All remote entities use `(serverId, remoteId)` compound identity.
- User-specific entities add `profileId`.
- Never assume remote IDs are globally unique.
- Download file identity must include remote file ID/inode when available.
- Room migrations are mandatory; destructive migration is prohibited in release builds.

## 13.2 Data freshness

Entities should include:
- `remoteUpdatedAt`;
- `lastFetchedAt`;
- `isDeleted`;
- `syncVersion` or ETag when available.

A websocket event may mark an entity stale and trigger refresh rather than carrying a complete trustworthy object.

---

# 14. Error handling

## 14.1 Error taxonomy

Use a sealed domain hierarchy:

```kotlin
sealed interface AppError {
    data class Network(...)
    data class Timeout(...)
    data class Authentication(...)
    data class Authorization(...)
    data class Validation(...)
    data class Server(...)
    data class ApiCompatibility(...)
    data class Storage(...)
    data class Download(...)
    data class Playback(...)
    data class Security(...)
    data class Conflict(...)
    data class Canceled(...)
    data class Unknown(...)
}
```

Do not throw generic `Exception` across layer boundaries.

## 14.2 Result model

Repositories return a typed result such as:

```kotlin
sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}
```

Exceptions may be used internally but must be translated at boundaries. Coroutine cancellation must always be rethrown.

## 14.3 Retry policy

- Read-only GET: up to 3 retries for transient network/5xx errors with exponential backoff and jitter.
- Authentication: no blind retry.
- `401`: require reauthentication or supported token renewal.
- `403`: no retry; refresh permissions.
- `404`: map to not found; refresh local entity if appropriate.
- `409`: refresh and present conflict.
- `429`: honor `Retry-After`; bounded retry.
- Writes: retry only when operation is proven idempotent or has a stable idempotency key.
- Downloads: resume by part/range; maximum retry count configurable.
- Websocket: reconnect indefinitely with capped backoff while profile remains active.

## 14.4 User-facing errors

Every user-facing error should include:
- plain-language summary;
- impact;
- action;
- optional technical code;
- retry when safe.

Examples:
- “This account is no longer allowed to download from this library.”
- “The book is still available on the server, but its offline copy is incomplete.”
- “Audiobookshelf removed the database entry. The source audio files were not deleted.”

Never show stack traces in ordinary UI.

## 14.5 Logging

- Structured events with category and correlation ID.
- Redact authorization, cookies, password fields, tokens in URLs, local filesystem paths, server host, username, media title, and description by default.
- Debug builds may enable expanded local logging through a developer toggle.
- Diagnostic export is ZIP/JSON/text with explicit redaction report.
- No analytics or crash service by default.
- Any future crash reporting is opt-in and strips private content.

---

# 15. Security and privacy

- HTTPS by default.
- Release build cleartext disabled unless the user explicitly enables a per-server local-network exception in an advanced screen; show warning.
- No certificate pinning by default because self-hosted certificates rotate and pinning can lock users out.
- No trust-all TLS mode.
- Tokens encrypted with Android Keystore-backed key material.
- Passwords never persisted.
- Database contains no raw tokens.
- Screenshot protection is optional for admin/user screens; do not block screenshots globally without reason.
- Exported Android components minimized.
- Media service exported only as required for media browsing and validates controller identities/commands.
- Deep links validate server/profile/item access.
- FileProvider uses narrow paths.
- No world-readable downloads.
- Sanitization for HTML descriptions and metadata-provider content.
- Filename/path sanitization.
- Destructive admin actions require online state, current authorization, confirmation, and audit log entry.
- Build dependency verification and locking enabled.
- Secret scanning in CI.
- No production secrets in repository.
- Network security config differs by debug/release.
- Local network permission changes for Android 16 must be handled and tested when connecting to LAN-only servers.
- App privacy policy should state that server credentials and listening data remain between the device and user-selected server unless optional crash reporting is enabled.

---

# 16. Code style, lint, formatter, and package management

## 16.1 Package/build manager

Use:
- Gradle Wrapper;
- Gradle Kotlin DSL;
- `gradle/libs.versions.toml` version catalog;
- dependency locking;
- dependency verification metadata;
- no dynamic versions;
- no `+` versions;
- no unpinned Git dependencies;
- repositories restricted to approved sources.

The selected stable Android Gradle Plugin, Kotlin, Compose BOM, Media3, Hilt, Room, WorkManager, OkHttp, Retrofit, and tooling versions must be pinned in the version catalog when the repository is initialized.

## 16.2 Formatter

Use ktlint as the single Kotlin formatter.

Required:
- `.editorconfig`;
- Kotlin official style;
- max line length 120;
- trailing commas enabled;
- no wildcard imports;
- final newline;
- UTF-8;
- LF line endings.

Commands:

```bash
./gradlew ktlintCheck
./gradlew ktlintFormat
```

Do not manually fight formatter output. Change the rule only with an architecture decision record.

## 16.3 Static analysis

Use:
- Android Lint;
- detekt with type resolution;
- Kotlin compiler warnings as errors in CI;
- Compose compiler reports/metrics in CI artifact when diagnosing regressions.

Commands:

```bash
./gradlew lintDebug
./gradlew detekt
```

Rules:
- no detekt baseline for new code;
- suppressions require a reason;
- no `GlobalScope`;
- no blocking I/O on Main;
- no swallowed exceptions;
- no empty catch blocks;
- no raw coroutine dispatchers outside injected dispatcher provider;
- no direct `System.currentTimeMillis()` in domain logic; use injected clock;
- no direct network DTO use in UI;
- no token-bearing URL logs;
- no unbounded Flow collection in lifecycle owners;
- exhaustive `when` on sealed domain types.

## 16.4 Naming and package conventions

Base package placeholder:

`com.example.shelfplayer`

Replace before signing/release.

Packages use feature/layer grouping, for example:

```text
com.example.shelfplayer.feature.player
com.example.shelfplayer.data.library
com.example.shelfplayer.playback.service
```

Naming:
- `*Screen` for route-level composables.
- `*Route` for navigation/wiring composables.
- `*ViewModel`.
- `*UiState`, `*UiEvent`, `*UiAction`.
- `*Repository` interface, `Default*Repository` implementation.
- `*Entity`, `*Dao`, `*Dto`.
- `*Worker`.
- `*UseCase` only when domain logic is non-trivial.
- Avoid `Manager`, `Helper`, and `Utils` unless the responsibility is specific and documented.

## 16.5 Required verification command

Provide one command that agents and CI can run:

```bash
./gradlew verifyDebug
```

`verifyDebug` must depend on:
- ktlintCheck;
- detekt;
- lintDebug;
- unit tests;
- Room schema verification;
- debug assembly.

A release verification command must additionally run instrumentation/managed-device tests and release lint.

---

# 17. Testing strategy

## 17.1 Test pyramid

### Unit tests
- series sequence parser/sorter;
- smart download candidate selection;
- network policy;
- retention/cleanup policy;
- progress conflict resolver;
- global track timeline conversion;
- buffer validation;
- permission evaluator;
- filename sanitizer;
- error mapper;
- profile switch transaction;
- settings precedence;
- auto-rewind buckets.

### Data/contract tests
- Retrofit serialization with captured fixtures.
- MockWebServer tests for auth, 401, 403, 404, 409, 429, 5xx.
- Range download and resume.
- Token redaction.
- Unknown/missing fields.
- Websocket reconnect and duplicate event handling.
- Room migrations.
- File commit/rollback after simulated crash.
- Capability resolver.

### Playback tests
- local single-file.
- local multi-file.
- remote stream.
- seek across track boundary.
- chapter navigation.
- audio focus loss.
- noisy route.
- media-button play/pause.
- process/service recreation.
- speed persistence.
- sleep timer.
- progress flush.

### UI tests
- login.
- profile switching.
- offline home.
- download state.
- permission-hidden management actions.
- metadata edit validation.
- destructive confirmations.
- TalkBack semantics.
- large font and landscape/tablet layouts.

### Server integration tests
Run a pinned Audiobookshelf container in CI or a dedicated integration workflow with fixture media:
- ordinary user;
- download-only user;
- editor;
- admin/root;
- two libraries;
- ordered series;
- multi-file book;
- missing/invalid item;
- metadata match provider test double where possible.

Because the published API reference may lag server behavior, contract tests against the selected server versions are release blockers.

## 17.2 Device/API matrix

At minimum test:
- API 26.
- API 31.
- API 34.
- API 36.
- phone portrait/landscape.
- tablet/foldable width.
- Bluetooth headset.
- wired headset or emulator route equivalent.
- Android Auto Desktop Head Unit where practical.
- offline mode.
- metered network transition.
- low storage simulation.
- process death.

## 17.3 Quality thresholds

Initial targets:
- domain/core unit coverage: 80% line coverage.
- smart download, progress sync, security, and deletion policies: 90%.
- no crash in 2-hour continuous playback soak test.
- no more than 10 seconds progress loss after forced process termination.
- player startup from cached local book under 1 second on reference device.
- cached library screen interactive under 1 second.
- scrolling grid maintains acceptable Compose performance on 2,000-item fixture library.
- no ANR in download/playback stress tests.

Coverage is a signal, not permission to write meaningless tests.

---

# 18. CI/CD

Use GitHub Actions unless another CI is selected.

## Pull request checks

1. Gradle wrapper validation.
2. Dependency verification.
3. `verifyDebug`.
4. Unit tests.
5. Lint and detekt reports.
6. Room schema diff.
7. Debug APK.
8. Secret scan.
9. License/dependency report.
10. Optional server contract tests when fixture container is available.

## Main branch/release checks

- managed-device tests;
- integration server tests;
- release build;
- release lint;
- signing only in protected CI;
- Software Bill of Materials;
- dependency vulnerability scan;
- mapping/native-symbol archive when applicable;
- reproducible version code/name;
- changelog generated from labeled changes.

## Branch policy

- Main is always buildable.
- Small pull requests.
- Every PR names requirement IDs.
- No merge with failing checks.
- Architecture changes add an ADR.
- Database migrations include old-to-new migration tests.
- API fixture changes require explanation.

---

# 19. Repository documentation

Required root files:

```text
README.md
PRODUCT_SPEC.md
AGENTS.md
CLAUDE.md
CONTRIBUTING.md
SECURITY.md
PRIVACY.md
LICENSE
CHANGELOG.md
.editorconfig
config/detekt/detekt.yml
docs/architecture/
docs/adr/
docs/api-compatibility.md
docs/testing.md
docs/release.md
```

`README.md` must include:
- purpose;
- unofficial status;
- screenshots later;
- prerequisites;
- build commands;
- how to connect a test server;
- verification command;
- license.

`docs/api-compatibility.md` must record:
- server versions tested;
- capabilities;
- known endpoint differences;
- fixtures;
- date last verified.

---

# 20. Implementation phases

## Phase 0 — Repository foundation

Deliver:
- Gradle project;
- module skeleton;
- Compose shell;
- Hilt;
- Room/DataStore;
- network foundation;
- formatter/lint/detekt;
- CI;
- error model;
- redacted logging;
- fake server/data source;
- architecture docs.

Exit criteria:
- `./gradlew verifyDebug` passes.
- App opens a fake library.
- No real credentials needed.

## Phase 1 — Authentication and cached browsing

Deliver:
- server profile;
- login;
- secure token storage;
- capability handshake;
- libraries/items sync;
- Room-backed home/library/search/details;
- profile switch.

Exit criteria:
- Two accounts on one server can switch.
- Offline cached browse works.
- Unauthorized libraries never appear.

## Phase 2 — Streaming player

Deliver:
- MediaLibraryService;
- remote playback session;
- ExoPlayer;
- global timeline;
- progress sync;
- notification/lockscreen/headset controls;
- speed/skip;
- buffer presets;
- audio focus/noisy handling.

Exit criteria:
- Two-hour streaming soak.
- Process/activity recreation.
- Media-button resume.
- Progress verified against server.

## Phase 3 — Downloads and offline playback

Deliver:
- download queue;
- foreground progress;
- resume;
- verification/atomic commit;
- local playback;
- offline outbox/session sync;
- storage management.

Exit criteria:
- Multi-file book downloads and plays offline.
- Network loss/restart resumes download.
- Forced process death loses no more than accepted progress.

## Phase 4 — Smart downloader and device automation

Deliver:
- series resolver;
- smart download policy;
- WorkManager constraints;
- retention;
- known output devices;
- per-device arm/auto-play/ask;
- sleep timer and auto-rewind.

Exit criteria:
- Next book queues once on unmetered network.
- Device connection policies are deterministic and debounced.
- No speaker surprise after headphone disconnect.

## Phase 5 — Management tools

Deliver:
- metadata edit;
- covers;
- quick/full match where supported;
- item/library scan;
- database removal;
- capability-gated source-file deletion;
- embed metadata;
- create user.

Exit criteria:
- Permission matrix tests pass.
- All destructive labels and confirmations pass UX review.
- Playback continues during management actions.

## Phase 6 — Android Auto, polish, release

Deliver:
- browsable media library;
- adaptive UI;
- accessibility;
- diagnostics;
- privacy/security docs;
- performance profiling;
- release pipeline.

---

# 21. Definition of Done

A requirement is complete only when:

- implementation references its requirement ID;
- acceptance criteria are met;
- unit/integration/UI tests exist at the correct level;
- formatter, lint, detekt, tests, and build pass;
- error, loading, empty, offline, and permission states are handled;
- accessibility semantics and large text are checked;
- no tokens/private data are logged;
- Room/API migrations or fixtures are included;
- relevant documentation is updated;
- screenshots or recordings are attached for UI changes;
- destructive actions have reviewed wording;
- playback regression is considered;
- no unrelated refactor is bundled;
- no TODO remains without an issue reference.

---

# 22. Agent implementation rules

1. Read `PRODUCT_SPEC.md`, `AGENTS.md`, and existing ADRs before modifying code.
2. Work on one vertical slice or requirement group at a time.
3. State assumptions in the pull request, not as hidden code behavior.
4. Do not invent server endpoints.
5. Add or update a contract fixture before relying on a new response shape.
6. Do not put tokens in URLs unless a tested endpoint has no header-based alternative and an ADR approves it.
7. Do not bypass TLS validation.
8. Do not call Retrofit directly from ViewModels.
9. Do not expose mutable Flow.
10. Do not use `GlobalScope`.
11. Do not use destructive Room migrations.
12. Do not claim source-file deletion from the documented database-delete endpoint.
13. Do not copy official app code without license review.
14. Prefer simple, testable policy classes over conditionals spread across screens.
15. Preserve active playback across ordinary UI and sync changes.
16. Run `./gradlew verifyDebug` before marking work complete.
17. When the API is unclear, add a failing contract test and capability-gated implementation rather than guessing.
18. Keep commits small and descriptive.
19. Update the API compatibility matrix with every new privileged endpoint.
20. Treat user-provided server addresses, usernames, titles, descriptions, filenames, and provider results as sensitive/untrusted.

---

# 23. Initial API mapping

The exact request/response models must be verified against the selected server version. The following documented operations define the initial adapter surface:

- Login and retrieve user/token.
- Get current user and permissions.
- Get accessible libraries.
- Get library items, authors, series, and progress.
- Start playback session for a library item.
- Sync open playback session.
- Close playback session.
- Create/update media progress.
- Sync local/offline listening session.
- Download individual item files and cover.
- Update item media/metadata.
- Upload/update/remove cover.
- Match item.
- Scan item.
- Scan library.
- Create/list/update users.
- Remove item from database.
- Embed metadata.

Important:
- The public API reference itself states that it is out of date.
- The documented item-delete operation removes the item from the database and does not delete files.
- Authentication and token behavior must be contract-tested against the target server.
- Websocket support must be verified through the reverse proxy.
- New API-key functionality may suit automation but does not automatically replace interactive user login for the mobile client.

---

# 24. Open decisions before public release

These do not block repository foundation or early vertical slices:

1. Final app name and application ID.
2. License choice.
3. Whether to publish on Google Play, F-Droid, GitHub Releases, or privately.
4. Minimum supported Audiobookshelf server version.
5. Whether custom CA certificate import is required for version 1.
6. Whether physical offline files are deduplicated across profiles in version 1.
7. Whether Android Auto ships in version 1 or 1.1.
8. Whether full metadata candidate search is reliable enough across supported server versions.
9. Whether true source-file deletion exists in the chosen server version and can be safely exposed.
10. Whether crash reporting is included at all.
11. Final default buffer presets after real-network testing.
12. Final progress sync cadence after battery/network profiling.
13. Whether cleartext LAN servers can be enabled in release builds.
14. Whether profile PIN/biometric protection is version 1 or 1.1.

Defaults chosen by this document remain in force until an ADR changes them.

---

# 25. Release acceptance checklist

## Authentication
- [ ] Multiple profiles.
- [ ] Token encrypted.
- [ ] Reauthentication preserves offline data.
- [ ] No secrets in logs.

## Playback
- [ ] Local and remote.
- [ ] Background service.
- [ ] Notification/lockscreen/headset.
- [ ] Android Auto if included.
- [ ] Audio focus and noisy handling.
- [ ] Progress survives process death.
- [ ] Buffer setting works.
- [ ] Speed/skip/sleep timer.

## Downloads
- [ ] Resume.
- [ ] Atomic completion.
- [ ] Verification.
- [ ] Wi-Fi/cellular policy.
- [ ] Smart next-book.
- [ ] Retention/cleanup.
- [ ] Low-storage handling.

## Profiles and permissions
- [ ] Fast switch.
- [ ] Per-profile state.
- [ ] Permission-gated UI and repository.
- [ ] Admin user creation.

## Management
- [ ] Edit metadata.
- [ ] Cover update.
- [ ] Match.
- [ ] Scan.
- [ ] Database-removal wording is exact.
- [ ] Source-file delete capability is safe or absent.

## Quality
- [ ] API 26/31/34/36 tested.
- [ ] Adaptive layout.
- [ ] TalkBack/large text.
- [ ] CI green.
- [ ] Dependency lock/verification.
- [ ] Privacy/security docs.
- [ ] Compatibility matrix.
- [ ] Two-hour playback soak.
- [ ] Offline sync test.
