# Garmin device management

**Classification:** Owner-requested product behavior; implementation contract pending watch-engine selection.
**Date:** 2026-10-07. Requirements SET-002, DL-001/003, AUTH-002/003/005, PLAY-007,
SYNC-001/002; PRODUCT_SPEC17/19/21. Android #119 remains the cross-repository umbrella.

## Requested settings flow

Settings → Playback → Devices contains a compact watch row. Selecting it expands its controls inline.
Preserve the existing output-device policy rows; a watch inventory is not the phone audio route list.

- Connected now: a small green status dot with accessible connection text. Otherwise show the actual
  last connection date/time, or Never connected. Do not infer connection from a cached snapshot.
- Last synced: date/time of the last acknowledged, completed device sync, or Never synced.
  SDK enqueue time and server refresh time are not successful watch synchronization.
- Force sync: request watch inventory/listening events, reconcile through existing Android owners,
  and return acknowledged state. Never start playback, overwrite a newer event, or select max(position).
- Downloads: a small, scrollable dialog showing the watch's reported inventory, including state/progress.
  Cached inventory remains explicitly dated when disconnected; unknown inventory is not an empty watch.
- New download: a small, scrollable dialog listing only completely downloaded Android books that the
  current unlocked profile is authorized to use. Selecting one queues a watch download request.
  Report Accepted/Downloading/Downloaded/Failed from actual watch responses; sending is not completion.

Watch sessions are watch listening events with captured book/profile/device identity, event time and
position. Android PHONE snapshots and Sidecar login sessions must never masquerade as watch listens.
Pending events survive watch/phone process death and are acknowledged idempotently after durable import.

## Observed external boundary

Inspected upstream [WatchShelf](https://github.com/JediBrooker/WatchShelf) commit
`93ac7507dae1cae1221509cefd97441dab36e955`. Its Sidecar exposes login, lean catalogue/files,
transcoding, cover and progress routes. It does not expose a watch inventory, device queue or watch
listening-session route. The watch app has no registered phone message receiver for this integration.
Its cached media/book/progress state belongs to its Audio Content Provider.

Garmin's [Media module](https://developer.garmin.com/connect-iq/api-docs/Toybox/Media.html) is for
Audio Content Provider contexts. The current BookWave Companion manifest is a `watch-app` with
Communications permission only; it cannot become an audio download engine just by calling Sidecar.
There is no current supported interface to manage an unmodified WatchShelf installation from BookWave.

The owner is selecting between a separate BookWave Audio Provider using the existing Sidecar and a
maintained WatchShelf integration fork. Do not claim either has been implemented. No WatchShelf code
has been copied. Any later reuse must retain its MIT copyright/license and review upstream notices.

Selecting a phone-downloaded book selects an authorized server item. Current Sidecar fetches its audio
from Audiobookshelf and transcodes watch-sized chunks; this is not a transfer of the phone's local files.
Missing server items, mismatched accounts and unavailable Sidecar must produce actual failures.
Normal Audiobookshelf tokens/passwords must never travel in the Companion protocol. Any provider's
Sidecar authentication needs its own explicit credential and server-identity boundary.

## Independent implementation responsibilities

Android owns Room-backed device timestamps, watch inventory/session projections and durable requests;
the existing download/library/profile repositories determine eligible phone books. Compose/ViewModels
invoke repository/use-case actions and never call Sidecar or Audiobookshelf directly.
The bridge retains bounded correlated delivery, session/generation checks and redaction before metadata.
Each device/provider reports capabilities; unsupported actions cannot look successful.

The selected watch engine owns media storage, queueing, native Wi-Fi sync, progress events and inventory.
Sidecar retains chunk preparation. The foreground Companion remains a separate live phone display.
Settings Force sync is account/device synchronization, not merely re-sending a PHONE snapshot.

## Automated verification required before delivery

- Contract fixtures for every used Sidecar route and every new watch command/report; tolerate unknown
  fields, reject invalid required fields, wrong profile/session/correlation and incompatible majors.
- Durable requests and event deduplication, acknowledgements only after persistence, bounded retry,
  stale/reordered events, profile switch/lock/removal/reauth and no autoplay or max-position reconciliation.
- Room migration and restart restoration for device timestamps, requests, inventories and events.
- Eligibility uses completed, current-profile-authorized phone downloads; recheck before queueing.
- Real Compose tests for Playback reachability, inline expansion, dialog selection/dismissal/scrolling,
  no false empty/success state and actual action wiring; source reversion proof for guarded fixes.
- Formatter and strict verifyDebug, forced after any classpath changes, Garmin target/test/package
  compilation, caller audit, shared fixture consistency and source-matched CI.

## Physical tests — all NOT RUN

| Case | Needed evidence |
| --- | --- |
| GD-01 | Pair exact phone/watch builds; connected dot tracks actual disconnect/reconnect, timestamps survive restart and time-zone changes. |
| GD-02 | Visual compact inline menu and dialogs at normal/200% text, long localized titles, rotation and mini-player clearance. Owner visual judgment only. |
| GD-03 | New download shows eligible phone books; tap queues the selected item on the correct watch/account without starting either player. |
| GD-04 | Native watch Wi-Fi download: charging/battery/storage admission, interrupted transfer, resume, duplicate tap, partial/corrupt chunk and accurate inventory status. |
| GD-05 | Listen with phone absent; chapter crossing, pause, deliberate rewind, completion, watch reboot and event recovery retain real book progress. |
| GD-06 | Force sync imports watch events and resolves phone/server conflicts by legitimate event time; repeat is idempotent and never starts playback. |
| GD-07 | Offline queueing, Sidecar/server outage, expired watch credentials and missing server book show truthful pending/failure state without leaking secrets. |
| GD-08 | Profile lock/switch/removal, mismatched provider account and a second watch cannot expose, download or reconcile another profile's books. |
| GD-09 | Android audio, sleep timer, downloads and progress remain continuous while inventory/download/sync dialogs and actions run. |
| GD-10 | Actual provider storage/restart/upgrade retention, sustained transfer/listening battery and Garmin Connect lifecycle; record model, firmware, Sidecar/source versions and both hashes. |

These extend G-01/G-02 and Q-01/A-08; they do not replace them or turn prior compile-only evidence
into device acceptance. The architecture choice gates the watch-download implementation.
