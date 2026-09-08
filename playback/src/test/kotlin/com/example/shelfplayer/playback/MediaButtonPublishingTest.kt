package com.example.shelfplayer.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * PRODUCT_SPEC PLAY-002 — a button set has to reach the **car**, not just the session.
 *
 * A device run reported the Car action never lighting up. The cause was not the icon, the state or the
 * drawable: `MediaSession.setMediaButtonPreferences(List)` does not refresh the legacy playback state a car
 * reads custom actions from, so the second publish in [MediaButtonPublishing] is what makes the change
 * visible. It looks redundant, and every reason it is not lives in third-party bytecode — so it is pinned
 * here instead of trusted to survive the next tidy-up.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class MediaButtonPublishingTest {

    @Test
    fun `a publish reaches the session and then the notification controller`() {
        val calls = mutableListOf<String>()

        MediaButtonPublishing.publish(
            buttons = listOf(button()),
            toAllControllers = { calls += "all" },
            toNotificationController = { calls += "notification" },
        )

        // Order matters as much as presence: the whole-session call is what sets the stored preferences the
        // per-controller refresh then republishes.
        assertEquals(listOf("all", "notification"), calls)
    }

    /** The notification controller is not connected for the whole of a session's life. */
    @Test
    fun `the session publish still happens with no notification controller`() {
        val calls = mutableListOf<String>()

        MediaButtonPublishing.publish(
            buttons = listOf(button()),
            toAllControllers = { calls += "all" },
            toNotificationController = null,
        )

        assertEquals(listOf("all"), calls)
    }

    @Test
    fun `both publishes carry the same buttons`() {
        val buttons = listOf(button(), button())
        val seen = mutableListOf<List<CommandButton>>()

        MediaButtonPublishing.publish(
            buttons = buttons,
            toAllControllers = { seen += it },
            toNotificationController = { seen += it },
        )

        assertEquals(listOf(buttons, buttons), seen)
    }

    private fun button(): CommandButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
        .setDisplayName("Car")
        .setSessionCommand(SessionCommand(NotificationButtons.ACTION_SELECT_CAR_OUTPUT, Bundle.EMPTY))
        .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
        .setEnabled(true)
        .build()
}
