# ADR-0020 — Downloads choose a volume, not a folder

- **Status:** Accepted
- **Date:** 2026-08-15
- **Requirements:** PRODUCT_SPEC DL-001, DL-002, DL-003, 3.3; ADR-0018 decision 4

## Context

ADR-0018 decision 4, in the owner's words:

> the files should live in the app folder, but it should also be possible to select a folder, and sd
> should be possible to select.

That is two requests, and they cost very different amounts.

**An SD card** is reachable through `Context.getExternalFilesDirs`, which returns this app's own directory
on every mounted volume. Those are ordinary `File` paths. They need no permission, no user grant and no
persisted URI, and the entire download pipeline — write a `.part`, verify it, rename it atomically, sweep
orphans, stat it at start-up — works on them without a line changing.

**An arbitrary folder** is reachable only through the Storage Access Framework, where paths do not exist.
A `DocumentFile` tree has no `renameTo`, no `length()` on a path, no `File` to hand `MediaMetadataRetriever`
or ExoPlayer without going through a `ContentResolver`. Supporting it means a second implementation of
every operation in `DownloadStorage`, a second one in `DownloadVerifier`, and a rename that is no longer
atomic — which is the property DL-001's "atomic commit prevents a crash from creating a false complete
state" rests on.

PRODUCT_SPEC 3.3 already lists *"Custom external download folders through Android's Storage Access
Framework"* under **later versions**.

## Decision

**Ship the volume picker. Defer the arbitrary folder.**

The storage screen lists internal storage and every removable volume the device has, with free space and a
*Removable* marker, and writes new downloads to the chosen one. Choosing an arbitrary folder through SAF
stays where the specification already put it.

This delivers the half of decision 4 that people actually run out of space over, at no risk to the download
core, and leaves the half the specification itself defers.

## How the choice is stored and resolved

**A UUID, not a path.** A removable volume's mount point is not stable across reboots, so a stored path
resolves to the wrong place or to nothing. `StorageManager` names the volume; the app resolves that name to
today's directory on every call, because a card can be removed between one file and the next.

**A missing volume falls back to internal storage** rather than failing. New downloads land somewhere real,
and nothing is deleted on the strength of an absent card.

## Changing it moves nothing, and that is the point

Every downloaded file's location is recorded **absolutely** in the manifest (ADR-0018: "Locations are URIs,
not paths"). So a book downloaded before the volume changed stays where it is and keeps playing, and the
setting is safe to change: nothing is copied, nothing is deleted, no long-running migration can fail
halfway.

The cost is that "where downloads go" and "where downloads are" are no longer the same question. Three
operations therefore read **every** root rather than the current one:

| Operation | Why every root |
| --- | --- |
| `deleteItem` | the book may be on the old volume; deleting only from the new one would report success and free nothing |
| `sweepOrphans` | an orphan on the old volume is invisible to everything else in the app, so nothing else would ever remove it |
| `deleteParts` | same reasoning as `deleteItem` |

Only writes use the current root. `usableBytes` reports the current root's free space, because it answers
"is there room for this download", and the download is going there.

## Consequences

- A card that is removed takes its books with it. They fail the start-up check, read as not downloaded, and
  offer a re-download — the same handling PLAY-003 already requires for any unreadable local file. No new
  code, and nothing is deleted.
- Files on external storage are **not app-private** in the way `filesDir` is: other apps with storage
  access can read them. DL-003 criterion 3 says downloads are not exposed by default, and the default is
  still internal storage, so the criterion holds; a user who chooses a card has chosen that.
- Uninstalling still removes them — an app-specific external directory is cleaned up with the app, unlike a
  SAF folder, which would survive. Worth knowing before the SAF half lands, because that difference will
  surprise somebody.
- `DownloadStorage` takes a `DownloadRoots` seam rather than the concrete `StorageVolumes`. It needs a list
  of directories and nothing else, and a test that had to build a DataStore and an application scope to ask
  where a file goes would be testing Hilt.
