package com.example.shelfplayer.playback

import androidx.media3.common.Player
import com.example.shelfplayer.core.model.playback.SkipIntervals
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** Issue #197 / PLAY-007 — which seek commands are relative jumps. */
class RelativeSeekCommandsTest {
    private val skips = SkipIntervals(back = 15.seconds, forward = 45.seconds)

    @Test
    fun `backward commands map to minus the back interval`() {
        listOf(
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
        ).forEach { command ->
            assertEquals((-15).seconds, RelativeSeekCommands.deltaFor(command, skips), "command $command")
        }
    }

    @Test
    fun `forward commands map to the forward interval`() {
        listOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_FORWARD,
        ).forEach { command ->
            assertEquals(45.seconds, RelativeSeekCommands.deltaFor(command, skips), "command $command")
        }
    }

    @Test
    fun `absolute seeks are not relative`() {
        assertNull(RelativeSeekCommands.deltaFor(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, skips))
        assertNull(RelativeSeekCommands.deltaFor(Player.COMMAND_SEEK_TO_MEDIA_ITEM, skips))
    }
}
