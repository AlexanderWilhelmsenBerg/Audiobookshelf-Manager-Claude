# The Android Auto player screen — what is available and what is worth doing

Written 2026-09-07, after the device run that produced ADR-0029 §8 and R-106/R-107.

This began as a **survey and a set of recommendations**, not a decision. Items 1, 2 and 3 have since been
built at the owner's request — see *What has since been built* — and the rest stand as recommendations.
Each item says what the platform documents, what BookWave does today, and what it would cost; where the
documentation stops short it says so instead of guessing (product priority 6).

**The numbering here is the recommended order**, which the sections now follow. An earlier draft numbered
them in the order they were written and then gave a different priority order at the end, which meant two
schemes for the same five items.

## What the player screen actually consists of

Android Auto's playback view is host-drawn from four things the app supplies:

| Element | Source | BookWave today |
| --- | --- | --- |
| Album art | `MediaMetadata.artworkUri` on the live item | Set from the playback session |
| Title | `MediaMetadata.title` | The book title |
| Subtitle (one optional line) | rendered from the legacy artist/subtitle fields | `Author • Series #N` (§6) |
| Elapsed time + progress bar | `PlaybackStateCompat` position and `METADATA_KEY_DURATION` | Correct; the book is one timeline (ADR-0016) |
| Explicit-content indicator | metadata | n/a |
| Primary control bar, left to right: **queue · previous · play/pause · next · custom** | media button preferences | play/pause, Car in the *previous* position and Headset in the *next* one, with both skips relocated to overflow |
| Overflow menu, up to **4** secondary actions | custom actions beyond/falling back from the bar | Car and Headset also declare overflow as fallback |

Two documented capacity numbers matter:

- the **minimised** control bar holds **up to five** controls, and expands to five more in a second row;
- an app may publish **up to six** custom actions, or **up to eight** if it does not use Next/Previous.

BookWave gives Car `SLOT_BACK` and Headset `SLOT_FORWARD` — the two positions Android Auto reserves for
*previous* and *next* and hands to custom actions when an app does not advertise those transport commands,
which BookWave does not (ADR-0016). PLAY-007's skips request the same two slots with overflow as their
fallback and are published second, so they are relocated to the overflow menu rather than dropped. An
earlier revision of this document described the *secondary* slots here; those are discarded by the legacy
conversion before a head unit sees them, which item 1 explains. What remains a host/device question is only
whether a given head unit draws what it is now unambiguously sent.

## 1. Move the output actions into the primary bar — the device finding, and it has an answer

**The finding.** *"The headset and car icon is gone from the smaller window."* Correct, and ADR-0029 §8
initially recorded it as a host-layout limit that could not be worked around. **That conclusion was wrong**,
and the reason is worth stating plainly: it was reasoned from `CommandButton`'s three slots the code already
used rather than from the class's actual API. Media3 1.11 declares **six**:

```
SLOT_CENTRAL   SLOT_BACK   SLOT_FORWARD   SLOT_BACK_SECONDARY   SLOT_FORWARD_SECONDARY   SLOT_OVERFLOW
```

`SLOT_BACK_SECONDARY` and `SLOT_FORWARD_SECONDARY` exist in the API, and **a car never sees them.** The
legacy conversion Android Auto is served by — `CommandButton.getCustomLayoutFromMediaButtonPreferences` —
branches on `SLOT_BACK`, `SLOT_FORWARD` and `SLOT_OVERFLOW` and on nothing else, so a button naming a
secondary slot falls through to the overflow branch and one naming it *without* an overflow fallback is
dropped outright. `MediaButtonSlotConversionTest` executes that conversion rather than restating it.

**Implemented in #78, and not as this section first described it.** The first attempt requested the
secondary slots; a device run reported the compact player unchanged, and the paragraph above is why it could
not have been otherwise. The owner then took the trade explicitly — *"I need them more than seek forward and
back"* — so the shipped layout is:

- Car requests `SLOT_BACK`, Headset requests `SLOT_FORWARD`, each with `SLOT_OVERFLOW` second.
- The skips request the **same two slots**, also with `SLOT_OVERFLOW` second, and are published *after* the
  output actions. Pass 1 of the conversion gives a contested slot to the first button naming it, so the
  outputs win and the skips are relocated to overflow rather than dropped.
- With an output action absent, the corresponding skip reclaims its slot, so the bar is never short.

**This is a real trade, not a free win.** PLAY-007's skips leave the compact bar on the car *and* on the
phone, which reads the same single layout — `docs/risks.md` R-109 records what it costs and that the owner
accepted it. One invariant is asserted rather than assumed: some button always holds `SLOT_BACK`, because if
it is ever vacated Media3 stops clearing `ACTION_SKIP_TO_PREVIOUS`, nothing here intercepts it, and a head
unit's *previous* restarts the book.

**What still needs a car.** Only whether the head unit draws what it is now unambiguously sent. Photograph
the minimised bar in the same run as R-107.

## 2. Reserve, or deliberately release, the seek slots

`MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_NEXT` / `..._SEEK_TO_PREV`, set through
`MediaSession.setSessionExtras`, tell the host whether to keep the prev/next positions blank when the app
does not support them or to fill them with custom actions.

**Not implemented, and deliberately so — an earlier version of this section claimed otherwise.** BookWave
briefly did call `setSessionExtras` with both reservation keys set to `false`; that call was removed as
measured dead code and there is no `setSessionExtras` anywhere in the repository now.

The reason is that the app does not own these keys. `MediaSessionLegacyStub` recomputes both from the custom
layout on every button update *and* on every `setSessionExtras`, as `!containsButtonForSlot(layout, SLOT_BACK
/ SLOT_FORWARD)`, overwriting whatever the app wrote. Under the layout in item 1 both slots are always
occupied, so Media3 computes `false` for both — exactly the intended answer, arrived at without the app
asserting it.

The decision itself still stands and is still worth stating: a book is one timeline window (ADR-0016), so
reserving empty seek positions would blank the very positions the output actions now occupy. It simply is
not this app's decision to publish. Do not treat "the reservations are explicitly false" as an invariant the
app maintains — it is inherited, and a layout change that vacates a primary slot flips it.

## 3. Say something when the server will not answer

A self-hosted server whose credentials have expired, or which is unreachable from the car's network, can
otherwise present as a book that simply does not start. Media3's compatibility error path gives the player
a way to explain that failure.

**Corrected and implemented 2026-09-07.** This section first said the documentation offered no way to attach
a resolution action, so the message had to be a bare "unlock this on your phone". That was a reading of the
guide rather than of the API. Media3 1.11 carries `ERROR_CODE_AUTHENTICATION_EXPIRED_COMPAT` together with
`EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT` and `..._INTENT_COMPAT`, and `MediaSession.sendError`
delivers them. `PlaybackFailureReport` now classifies the credential/network cases; an expired-credential
message carries a labelled button that opens the app, while an unreachable-server case is explained without
pretending the head unit can fix connectivity.

This remains device-host behaviour: the mapping and session call exist in the implementation, while the
exact way a particular Android Auto host renders the sentence/action must be checked in the car.

## 4. Metadata the player can draw and BookWave does not send

`androidx.car.app.mediaextensions.MetadataExtras` carries several keys the player screen honours:

- **`KEY_DESCRIPTION_LINK_MEDIA_ID` — shipped.** `MediaItems.queueFor` writes it on the playing item,
  pointing at `tab/history`, with a label on `MediaMetadata.description` for it to attach to. It is the
  nearest reachable thing to the owner's request that the queue button open History (item 5 explains why
  the queue itself cannot), and whether a head unit renders it is #127's device check. Note the asymmetry
  in the documentation: AOSP's customisation guide says Automotive OS OEMs **must** render these as
  tappable, while `developer.android.com` hedges for projected Android Auto with *"if the car supports
  this feature"*.
- **`KEY_SUBTITLE_LINK_MEDIA_ID` — not shipped**, and the untaken alternative rather than a second
  opportunity: the subtitle already carries `Author • Series #N`, so linking *it* to the series node would
  turn the byline into navigation, but the description was the free line and History was what was asked for.
- **`KEY_CONTENT_FORMAT_TINTABLE_LARGE_ICON_URI` / `..._SMALL_...`** — a format badge beside the title.
- **`KEY_IMMERSIVE_AUDIO`** — an indicator; not applicable to Audiobookshelf content.

**Do not schedule any *further* keys from this family before the shipped one is tested.** The Media3 half of
the old question here is now answered: disassembling `LegacyConversions.convertToMediaMetadataCompat` shows
it iterates `MediaMetadata.extras` and forwards String entries, so the extra does reach the legacy
`MediaMetadataCompat`. What stays open is only whether the **host** reads it — androidx/media#2127 remains
open with no maintainer answer, and the note that its documentation "still uses legacy code". BookWave now
has two bets in this family, and the description link above is the one that will be looked at first because
the owner asked for it. The other:
`EXTRAS_KEY_COMPLETION_PERCENTAGE` on browse rows, which R-10 records as unverified for the same reason.
The honest sequencing is to verify the mechanism once, on a head unit, with the completion percentage that
is already there — and only then decide whether to add more.

## 5. Chapters as the media session queue — the one real restructuring

The primary bar's **far-left position is queue access**, and BookWave does *not* leave it empty — an earlier
version of this section said it did. `MediaSessionLegacyStub` publishes a queue whenever the timeline is
non-empty and `COMMAND_GET_TIMELINE` is available, both of which hold, so a car is already offered a queue
of one row: the current book. Filling it with chapters would give the driver a native chapter list *on the
player screen*, plus a "Now playing" marker via `setActiveQueueItemId`, instead of a `Chapters` node inside
Library.

**And the queue cannot be repurposed for anything else.** The owner asked for History there. A queue row's
only meaning to a car is *play this now* — `onSkipToQueueItem` resolves to `seekToDefaultPosition(index)` —
so a History row tapped in the queue would interrupt the book (priority 1) and make every position writer
name the wrong one (priority 2). `docs/risks.md` R-110 records that, and the description link shipped in its
place.

**This is a genuine trade against ADR-0016, and should not be done casually.** Media3 derives the legacy
queue from the player's timeline, so a chapter queue means one media item per chapter rather than one per
book. ADR-0016 chose one window deliberately, and PLAY-007's notification buttons exist because Media3's
default *previous* seeked to zero and a device run caught it **restarting a thirty-four-hour book**. One
item per chapter changes the meaning of every seek, every position write, and both skip buttons.

Two ways to have most of it without paying that:

- **A queue of one.** Publish the book as a single queue item with a title. Cheap, and it fills the slot
  with something honest, but it tells the driver nothing they cannot already see.
- **Keep the timeline and expose chapters only as browse.** What BookWave does now. The chapter list is
  correct and complete; it is merely further away than the queue slot would be.

My recommendation is to **leave ADR-0016 alone** unless a device run shows drivers reaching for the queue
button. The prize is one tap; the risk is the seek model of the whole app.

## What has since been built

Items 1, 2 and 3 were applied on 2026-09-07 at the owner's request.

- **1 — output actions in the primary bar, on the second attempt.** The first declared
  `SLOT_BACK_SECONDARY` / `SLOT_FORWARD_SECONDARY`, and a device run showed the compact player unchanged:
  those slots are discarded by the legacy conversion before any head unit sees them. Car now takes
  `SLOT_BACK` and Headset `SLOT_FORWARD`, published ahead of the skips, which request the same slots with
  overflow as their fallback and are relocated there rather than dropped. The owner accepted losing the
  skips from the compact bar — on the phone as well as the car — to get this; R-109.
- **2 — the seek-slot reservation: reverted, not shipped.** The `setSessionExtras` call this document once
  described was removed as measured dead code. Media3 recomputes both keys from the custom layout on every
  update and overwrites the app's value, and under item 1's layout it computes exactly the `false` that was
  wanted. The value is inherited, not asserted — do not rely on it as an app-maintained invariant.
- **3 — failure reporting.** `MediaSession.sendError` with `PlaybackFailureReport` deciding between an
  expired credential and an unreachable server, and **this document was wrong about the ceiling**: it said
  the documentation offered no way to attach a resolution action. Media3 1.11 has
  `ERROR_CODE_AUTHENTICATION_EXPIRED_COMPAT` plus `EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT` and
  `..._INTENT_COMPAT`, so the credential message carries a labelled button that opens the app.

Two answers to the owner's follow-up questions, both checked against the API rather than assumed:

- **Chapter *n* of *m* is available.** `MediaMetadata` carries `trackNumber` and `totalTrackCount`, and
  `Player.replaceMediaItem` is the documented way to update a playing item's metadata **without
  interrupting playback**, position preserved — so the byline can change at each chapter boundary.
  [androidx/media#2993](https://github.com/androidx/media/issues/2993) reported a `MediaItem` leak on
  repeated calls; once per chapter is infrequent enough to accept, but the fixed version should be checked.
- **Chapter-relative *progress* is not.** The progress bar is the timeline, and the timeline is the book
  (ADR-0016).
- **History cannot go in the queue slot.** Not a trade-off — `MediaSession` has no queue API at all in
  Media3, and the legacy queue is derived from the player's timeline, so nothing that is not a timeline
  window can be put there — and a queue row's only meaning to a car is *play this now*, since
  `onSkipToQueueItem` resolves to `seekToDefaultPosition(index)`, so a History row tapped there would
  interrupt the book. **What shipped is `KEY_DESCRIPTION_LINK_MEDIA_ID`**, set on the playing item and
  pointing at `tab/history`; `MediaItems.queueFor` writes that key and no other. `KEY_SUBTITLE_LINK_MEDIA_ID`
  is the untaken alternative — the subtitle already carries `Author • Series #N`. Inspect the
  **description** field when validating this on a head unit (#127).

## Still open

Item 4 is **partly shipped**: `KEY_DESCRIPTION_LINK_MEDIA_ID` is on the playing item and awaiting a device
check (#127). The remaining keys stay a **measurement before they are features** — the Media3 forwarding
half is now settled by disassembly, so what is left to verify is host rendering, and the shipped link is the
cheapest thing to verify it with. Item 5 is **closed as impossible** in the form asked for: the queue cannot
carry History, because `onSkipToQueueItem` resolves to `seekToDefaultPosition(index)` and a row tapped there
would interrupt the book. Chapters-as-queue remains theoretically open but needs evidence that drivers reach
for the queue button, and reopening ADR-0016 deliberately if they do.

## What this survey does not claim

The API readings above do not prove a particular head unit's rendering. Items 1 and 3 are implemented and
item 4 partly so, item 2 was reverted as measured dead code, and item 5 is closed as impossible — but the
primary-slot takeover, the lit output glyphs, the description link and the error presentation have all yet
to be accepted on a head unit. #126 and #127 track the two the owner has already reported back on. This project's own record is that the car keeps finding what the documents do not say —
R-10 covers exactly that gap, and ADR-0029 §8's original "nothing else on the player can show it" conclusion
was itself drawn from a partial reading of an API. Treat capacity numbers and slot rendering as the host's to
confirm.

## Sources

- [Android for Cars — media apps](https://developer.android.com/training/cars/media)
- [Enable playback control](https://developer.android.com/training/cars/media/enable-playback) — the
  reserved skip slots, custom action ordering, queue APIs
- [Handle errors](https://developer.android.com/training/cars/media/errors)
- [Customize playback controls](https://developers.google.com/cars/design/create-apps/media-apps/customize-playback-controls)
  — up to 6 custom actions, or 8 without Next/Previous
- [Playback view](https://docs.partner.android.com/drivingux/android-auto/apps/playback-view) — the primary
  bar's order, the queue position, 4 overflow actions
- [Customize media (AOSP)](https://source.android.com/docs/automotive/hmi/media/customization) — what OEMs
  must honour for the subtitle/description links and format badges
- [androidx/media#2127](https://github.com/androidx/media/issues/2127) — whether Media3 metadata extras
  reach Android Auto, open
- `androidx.media3:media3-session:1.11.0`, read from the artifact: `CommandButton`'s six slots,
  `MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_*`, `MediaSession.setSessionExtras`
