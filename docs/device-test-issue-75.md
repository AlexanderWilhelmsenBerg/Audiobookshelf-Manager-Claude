# Device acceptance — issue #75 mini-player session reattachment

**Scope:** Android notification/session entry and Activity lifecycle only.  
**Automated proof:** `:playback` Media3/Robolectric existing-session attachment and stale-token rejection plus `:app` STARTED-lifecycle reachability.  
**Still physical:** notification content intent, task/Activity recreation, real service lifetime and OEM media surfaces.

## Notification entry

1. Start an audiobook in BookWave and confirm the media notification is present.
2. Leave BookWave while playback continues. Where practical, destroy/recreate the Activity without stopping the playback service.
3. Tap the BookWave media notification.
4. Confirm BookWave opens and the mini player appears once session state is available.
5. Confirm title, position and play/pause state match the ongoing notification/session.
6. Tap the mini player and confirm the full player opens.
7. Pause/resume, skip and seek from BookWave; confirm the notification follows the same session.

## Global navigation invariant

With the book still loaded, navigate through representative normal destinations:

- Home
- Library/Browse
- Search
- Downloads
- Settings

The mini player remains available on each normal screen. While the full player is expanded, a separate mini player is not required.

## Recreation and foreground return

With playback running, exercise:

1. background -> foreground;
2. configuration/rotation recreation where supported;
3. Activity destruction/recreation while the playback service survives.

The mini player returns without a new Play command, queue replacement or playback restart.

## Idle-launch regression

1. Stop playback completely and ensure the session contains no loaded media.
2. Dismiss/confirm absence of the playback notification.
3. Close BookWave and launch it normally from the launcher.
4. Confirm no mini player appears.
5. Confirm no playback notification/service is created merely by opening the app.

## Stop / clear

With a loaded book and visible mini player:

1. Stop playback from BookWave.
2. Confirm the loaded media is cleared.
3. Confirm the mini player disappears and does not return on navigation/foregrounding.

## System-owned origin

Where practical, start or resume playback while the phone UI is absent using a headset/media button or Android Auto. Then open the phone app and confirm the mini player projects that live session without issuing Play again.

Also repeat with BookWave already foreground: cause a legitimate system-owned/session start without leaving the Activity and confirm the STARTED lifecycle observer projects the newly published live session without restarting playback.

## Pass condition

Issue #75 is physically accepted when notification entry, recreation, navigation, idle launch and stop/clear all match the loaded-session invariant with no duplicate session, unexpected service startup or manufactured notification.
