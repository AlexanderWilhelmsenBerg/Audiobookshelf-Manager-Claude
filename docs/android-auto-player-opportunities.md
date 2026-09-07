# The Android Auto player screen — what is available and what is worth doing

Written 2026-09-07, after the device run that produced ADR-0029 §8 and R-106/R-107.

This is a **survey and a set of recommendations**, not a decision. Nothing here is implemented. Each item
says what the platform documents, what BookWave does today, and what it would cost — and, where the
documentation stops short, it says that instead of guessing (product priority 6).

## What the player screen actually consists of

Android Auto's playback view is host-drawn from four things the app supplies:

| Element | Source | BookWave today |
| --- | --- | --- |
| Album art | `MediaMetadata.artworkUri` on the live item | Set from the playback session |
| Title | `MediaMetadata.title` | The book title |
| Subtitle (one optional line) | rendered from the legacy artist/subtitle fields | `Author • Series #N` (§6) |
| Elapsed time + progress bar | `PlaybackStateCompat` position and `METADATA_KEY_DURATION` | Correct; the book is one timeline (ADR-0016) |
| Explicit-content indicator | metadata | n/a |
| Primary control bar, left to right: **queue · previous · play/pause · next · custom** | media button preferences | play/pause, and the two skips |
| Overflow menu, up to **4** secondary actions | custom actions beyond the bar | Car and Headset live here |

Two documented capacity numbers matter, because both are larger than what BookWave uses:

- the **minimised** control bar holds **up to five** controls, and expands to five more in a second row;
- an app may publish **up to six** custom actions, or **up to eight** if it does not use Next/Previous.

BookWave publishes four buttons into three slots and puts the two the driver most needs into overflow.

## 1. Move the output actions into the primary bar — the device finding, and it has an answer

**The finding.** *"The headset and car icon is gone from the smaller window."* Correct, and ADR-0029 §8
recorded it as a host-layout limit that could not be worked around. **That conclusion was wrong**, and the
reason is worth stating plainly: it was reasoned from `CommandButton`'s three slots the code already used
rather than from the class's actual API. Media3 1.11 declares **six**:

```
SLOT_CENTRAL   SLOT_BACK   SLOT_FORWARD   SLOT_BACK_SECONDARY   SLOT_FORWARD_SECONDARY   SLOT_OVERFLOW
```

`SLOT_BACK_SECONDARY` and `SLOT_FORWARD_SECONDARY` are the two further positions in the primary bar — the
ones that take it from three controls to the documented five. BookWave uses `SLOT_BACK`, `SLOT_FORWARD` and
`SLOT_OVERFLOW`, and nothing else.

**Recommendation.** Publish Car and Headset in `SLOT_BACK_SECONDARY` and `SLOT_FORWARD_SECONDARY` instead
of `SLOT_OVERFLOW`. Small, local, and it needs no new state — the buttons and their labels already exist.
The skips keep their slots, so PLAY-007 is untouched and nothing is traded away.

**What still needs a car.** Whether a given head unit draws the secondary slots in its *minimised* bar is a
host decision, exactly like R-107's indicator bar. The change cannot make things worse — overflow is where
they are now — but the claim "they appear in the small window" is not proven until a head unit does it.
Worth photographing both bars in the same run as R-107.

## 2. Reserve, or deliberately release, the skip slots

`MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_NEXT` / `..._SEEK_TO_PREV`, set through
`MediaSession.setSessionExtras`, tell the host whether to keep the prev/next positions blank when the app
does not support them or to fill them with custom actions. **BookWave never calls `setSessionExtras` at
all**, so it takes the default and has never made this choice.

It matters here because a book is one timeline window (ADR-0016), so Media3 reports no skip-to-next or
skip-to-previous command — which is exactly the condition the reservation keys govern. Leaving them unset
means the host may already be free to place custom actions there; declaring them explicitly makes the
layout intentional rather than incidental. Cheap either way, and it should be decided alongside item 1
rather than separately, since the two compete for the same positions.

## 3. Chapters as the media session queue — the one real restructuring

The primary bar's **far-left position is queue access**, and BookWave leaves it empty. Filling it gives the
driver a native chapter list *on the player screen*, plus a "Now playing" marker via
`setActiveQueueItemId`, instead of a `Chapters` node four taps into the browse tree.

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

## 4. Say something when the server will not answer

`PlaybackStateCompat.STATE_ERROR` with `setErrorMessage(code, message)` puts **a localised sentence in front
of the driver** on the player screen. BookWave has a real use for it that it does not currently serve: a
self-hosted server whose credentials have expired, or which is unreachable from the car's network. Today
that presents in the car as a book that does not start.

The documentation is explicit that the message must be localised and must say what the person has to do —
and it stops short of describing any way to attach a resolution action or intent, so **a sign-in cannot be
completed from the car** and the message has to be "unlock this on your phone". That is a documentation
limit, not a design choice; it should be written as such rather than assumed to be richer.

This is the highest-value item after #1, because it converts a silent failure into an explained one, and it
touches no routing or playback state at all.

## 5. Metadata the player can draw and BookWave does not send

`androidx.car.app.mediaextensions.MetadataExtras` carries several keys the player screen honours:

- **`KEY_SUBTITLE_LINK_MEDIA_ID` / `KEY_DESCRIPTION_LINK_MEDIA_ID`** — the subtitle or description becomes
  **tappable**, opening a browse node. AOSP's customisation guide says OEMs *must* render these as
  tappable and open the linked item. For BookWave this is the obvious one: the subtitle is already
  `Author • Series #N`, and linking it to the series node turns the byline into navigation.
- **`KEY_CONTENT_FORMAT_TINTABLE_LARGE_ICON_URI` / `..._SMALL_...`** — a format badge beside the title.
- **`KEY_IMMERSIVE_AUDIO`** — an indicator; not applicable to Audiobookshelf content.

**Do not schedule any of these before testing one.** Whether `MediaMetadata.extras` set through Media3
reaches the legacy `MediaMetadataCompat` that Android Auto reads is an **open, unresolved question
upstream** — androidx/media#2127, still open, with no maintainer answer and the note that the
documentation for it "still uses legacy code". BookWave already has one bet in this family:
`EXTRAS_KEY_COMPLETION_PERCENTAGE` on browse rows, which R-10 records as unverified for the same reason.
The honest sequencing is to verify the mechanism once, on a head unit, with the completion percentage that
is already there — and only then decide whether to add more.

## Recommended order

1. **Output actions into the secondary primary-bar slots** (item 1) — answers a device finding, small, no
   new state, no trade.
2. **Decide the slot reservation explicitly** (item 2) — same area, same review, one line.
3. **Error messaging for an unreachable or expired server** (item 4) — turns silence into a sentence,
   touches nothing risky.
4. **One metadata-extras experiment** (item 5) — but as a *measurement* on the existing completion
   percentage first, not as a feature.
5. **Chapters as a queue** (item 3) — only with evidence, and only with ADR-0016 reopened deliberately.

## What this survey does not claim

Every item above is a documentation reading plus a look at Media3 1.11's compiled API. **None of it has
been seen on a head unit**, and this project's own record is that the car keeps finding what the documents
do not say — R-10 covers exactly that gap, and ADR-0029 §8's "nothing else on the player can show it" was
itself a wrong conclusion drawn from a partial reading of an API. Treat the capacity numbers and slot
behaviour as the host's to confirm.

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
