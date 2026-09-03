package com.example.shelfplayer.core.model.playback

import com.example.shelfplayer.core.model.LibraryItemId
import kotlin.time.Duration

/**
 * PRODUCT_SPEC SYNC-002 — a position this device paused at **and the server confirmed it holds**.
 *
 * ### The fact the freshness check actually needs
 *
 * Deciding whether another device has moved a book is a comparison, and a comparison needs a reference
 * point that is true on both sides. Four attempts used the wrong one:
 *
 *  - the server's `lastUpdate` against the row's `updatedAt` — two clocks, and the app's own
 *    server-sourced write made them equal (`docs/risks.md` R-88);
 *  - how long ago the listener pressed pause — which says nothing about anybody else (R-89);
 *  - `hasUnsyncedChanges` — persistence bookkeeping, raised by every journal tick and lowered by an
 *    upload, so a record that landed after its own acknowledgement left it standing forever (R-92, R-93);
 *  - the player's live position — which is what we are trying to judge, not evidence about it.
 *
 * This is the reference point that is a *fact about both sides*: at some moment this device stopped at
 * [position], sent it, and Audiobookshelf answered that it had taken it. From that instant the server and
 * this device agreed. So if the server is now reporting something else, **somebody else moved it** — no
 * timestamps, no clock comparison, no inference from local bookkeeping.
 *
 * ### Why a generation number travels with it
 *
 * The acknowledgement arrives from the network, later than the pause that produced it. In between, the
 * listener can seek, skip, cross a chapter or start playing again — each of which makes [position] no
 * longer a description of where this device is at rest. [generation] is bumped by every one of those
 * moves, so a late acknowledgement can only ever confirm the pause it belongs to. See `ResumeBaseline`.
 *
 * Existing at all means acknowledged: an unconfirmed pause never becomes one of these, which is how
 * "failed pause acknowledgement" stays distinguishable from "confirmed, and the server agrees".
 */
data class AcknowledgedPause(val bookId: LibraryItemId, val position: Duration, val generation: Long)
