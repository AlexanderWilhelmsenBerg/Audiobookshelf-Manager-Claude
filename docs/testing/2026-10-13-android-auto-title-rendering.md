# Android Auto title rendering — #265 (13 October 2026)

## Requested layout
1. Full book title, ideally two lines in smaller but safely readable text.
2. Series sequence first, then series name (example `#12.5 Tidewatch`).
3. Author.

## Verified boundary
- The BookWave `MediaLibraryService`/Media3 API supplies a complete primary title, separate series subtitle, artist, album title and per-parent grid/list *hints*. It does not supply an override for Android Auto title font size, primary-title line count or a third row of text.
- Physical head-unit photos recorded on #251 after PR #254: long titles still appeared as a clipped single line; this host displayed artist and omitted series subtitle in browsing. The earlier code had preserved the full canonical title, so this was a host presentation limitation.
- Both Android Auto browse and the live MediaSession project a **series-first visible byline** in `artist`, because a physical head-unit test proved the host reads that field and ignores subtitle. #265 sets `#number Series • Author` so the first secondary information is the volume identifier; standalone rows still have an author-only artist. The structured series subtitle is still supplied separately. The host may clip the byline, so the author may still not get its own visible third line.
- `SeriesDisplayLabel.primary` is a shared display-only formatter for the current primary-or-first membership. Numeric series sort order, identifiers, author source fields, and media items are not changed.

## Host experiment, not part of this metadata PR
Google's [content-style guide](https://developer.android.com/training/cars/media/create-media-browser/content-styles) says list items prioritize text/metadata and grid items prioritize artwork. BookWave's accepted PD-001 chooses artwork-first grids for Continue, Series and Authors. A switch to lists may improve visibility on some hosts but sacrifices the desired cover-first layout; it is **not proof** of multiline titles.

Google's [typography guidance](https://developers.google.com/cars/design/android-auto/design-system/typography) is host design guidance, not app-settable typography on MediaLibraryService surfaces. Custom template media playback likewise reads the host MediaSession and does not promise three custom rows.

## Manual acceptance on the same book / physical head unit
- Compare a short book, a very long book and a series book with a decimal sequence, both in browse and Now Playing.
- Record visible number of title lines, whether the series-first byline appears, and whether author remains readable. Screenshot with no user/account data disclosed.
- Compare list vs grid only in a separately approved experiment; do not change PD-001 by inference.
- Existing full-title metadata tests remain required, but their PASS is not physical rendering proof.


## Experimental text-first contextual library browse — October 14, 2026

The owner's physical PR #266 APK test confirmed that number-first metadata appeared but book and series names remained clipped in artwork-first grids. The subsequent requested comparison is based on Absorb's list-oriented, two-field media display rather than copying its Flutter UI:

- **Book rows:** full book title as `MediaMetadata.title`, numbered series label as `subtitle` and host-visible `artist`; do not append author to the book-row byline.
- **Series rows:** full series name as `title`, all distinct credited authors as `subtitle` and `artist`; leave the navigation item browsable, not playable.
- **Presentation hint:** list items for Continue, individual book children under Series/Authors, and the direct recent row; retain artwork metadata. The car decides the actual image size, line wrapping and truncation.
- The MediaSession **Now Playing** byline is separate and unchanged.
- This differs from accepted PD-001 artwork-first cards; leave the PR draft until car screenshots support a deliberate owner decision.

Use a long title, a multi-book series with one author, a mixed-author series, and a standalone book to check this host. Photos should show title/series legibility plus cover visibility for both browse types.
