package com.example.shelfplayer.core.model.playback

import java.time.Instant
import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-004 / SYNC-002 — what the server has stored for one book, read straight back.
 *
 * `GET /api/me/progress/{itemId}`, captured as `media-progress.json`. Deliberately slimmer than
 * [com.example.shelfplayer.core.model.library.MediaProgress]: that one is what *this device* holds and
 * carries a profile, a server and an unsynced flag, none of which the wire response has. Two types rather
 * than one nullable-everything type, because the question "what did the server say" and the question "what
 * do we hold" have different answers and conflating them is how a local value gets reported as a server
 * fact.
 *
 * @property position the server's `currentTime`, in **seconds** on the wire.
 * @property updatedAt the server's `lastUpdate`, in **epoch milliseconds** on the wire. The field that
 *   makes a freshness comparison possible: it moves when any client writes progress, so comparing it
 *   against the moment this device last recorded a position answers "has somebody else been here since"
 *   without either side leaving the server's own clock.
 */
data class ServerProgress(val position: Duration, val updatedAt: Instant, val isFinished: Boolean)
