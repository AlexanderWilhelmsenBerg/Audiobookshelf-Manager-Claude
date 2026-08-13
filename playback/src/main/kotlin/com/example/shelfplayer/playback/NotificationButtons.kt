package com.example.shelfplayer.playback

import androidx.media3.session.CommandButton
import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-001 / PLAY-007 — what the media notification's two side buttons are.
 *
 * ### Why they are ours rather than Media3's
 *
 * Left to itself `DefaultMediaNotificationProvider` fills the slots either side of play/pause with **skip to
 * previous** and **skip to next**. On a playlist of songs that is right. On a book — which since ADR-0016 is
 * a *single* timeline window — "previous" has nowhere to go, so Media3 seeks to zero: a device run found
 * that pressing it **restarted a thirty-four-hour book**. That is a data-loss-shaped bug for a listener who
 * then lets it play (product priority 2), and there is no undo on a notification.
 *
 * So the app puts its own buttons in those slots. `CommandButton.SLOT_BACK` and `SLOT_FORWARD` are exactly
 * the supported mechanism: the provider takes the first enabled preference declaring each slot and uses it
 * *instead of* the default, so replacing them is a matter of publishing preferences rather than of
 * subclassing anything.
 *
 * They carry a **session** command rather than a player command deliberately — the provider filters its
 * incoming preferences down to enabled custom-session-command buttons, so a button wired to
 * `COMMAND_SEEK_BACK` would be dropped and the default restored.
 *
 * ### The icons
 *
 * Media3's numbered glyphs have the number drawn into them. Same rule as the in-app buttons (`SkipIcons`):
 * the numbered glyph when it tells the truth, a plain arrow when it would not. A button reading "30" that
 * jumps forty-five seconds is worse than one with no number, because nothing tells the user to distrust it.
 * The seconds are always in the display name, which is what a screen reader announces.
 */
internal object NotificationButtons {

    /** The notification's back button; jumps by the configured interval rather than to the previous item. */
    const val ACTION_SKIP_BACK = "com.example.shelfplayer.playback.SKIP_BACK"

    /** The notification's forward button. */
    const val ACTION_SKIP_FORWARD = "com.example.shelfplayer.playback.SKIP_FORWARD"

    /** PRODUCT_SPEC PLAY-008 — the notification action that extends a running sleep timer. */
    const val ACTION_EXTEND_SLEEP_TIMER = "com.example.shelfplayer.playback.EXTEND_SLEEP_TIMER"

    fun backIcon(interval: Duration): Int = when (interval.inWholeSeconds) {
        FIVE -> CommandButton.ICON_SKIP_BACK_5
        TEN -> CommandButton.ICON_SKIP_BACK_10
        FIFTEEN -> CommandButton.ICON_SKIP_BACK_15
        THIRTY -> CommandButton.ICON_SKIP_BACK_30
        else -> CommandButton.ICON_SKIP_BACK
    }

    fun forwardIcon(interval: Duration): Int = when (interval.inWholeSeconds) {
        FIVE -> CommandButton.ICON_SKIP_FORWARD_5
        TEN -> CommandButton.ICON_SKIP_FORWARD_10
        FIFTEEN -> CommandButton.ICON_SKIP_FORWARD_15
        THIRTY -> CommandButton.ICON_SKIP_FORWARD_30
        else -> CommandButton.ICON_SKIP_FORWARD
    }

    private const val FIVE = 5L
    private const val TEN = 10L
    private const val FIFTEEN = 15L
    private const val THIRTY = 30L
}
