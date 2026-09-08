package androidx.media3.session

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.example.shelfplayer.playback.MediaButtonLayout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC PLAY-002 / PLAY-007 — the app's own button order, run through the car's real conversion.
 *
 * `MediaButtonSlotConversionTest` proves what Media3 does with a given order. This proves BookWave produces
 * that order — a distinction a red-check forced: reverting the ordering in `PlaybackService` broke nothing,
 * because the conversion tests build their own lists. Living in Media3's package so it can call the same
 * package-private conversion the legacy stub runs, rather than restating its behaviour.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class MediaButtonLayoutTest {

    @Test
    fun `the output actions are published before the skips`() {
        val ordered = MediaButtonLayout.inPriorityOrder(
            outputActions = listOf(button("car", CommandButton.SLOT_BACK)),
            skipActions = listOf(button("skipBack", CommandButton.SLOT_BACK)),
            overflowActions = listOf(overflow("sleep")),
        )

        assertEquals(listOf("car", "skipBack", "sleep"), ordered.map { it.displayName.toString() })
    }

    /**
     * The property the owner actually asked for, asserted end to end: with BookWave's real arrangement, an
     * output action — not a skip — is what the car puts in its control bar.
     */
    @Test
    fun `an output action reaches the car's bar and the skips fall to overflow`() {
        val layout = convert(
            MediaButtonLayout.inPriorityOrder(
                outputActions = listOf(
                    button("car", CommandButton.SLOT_BACK),
                    button("headset", CommandButton.SLOT_FORWARD),
                ),
                skipActions = listOf(
                    button("skipBack", CommandButton.SLOT_BACK),
                    button("skipForward", CommandButton.SLOT_FORWARD),
                ),
                overflowActions = listOf(overflow("sleep")),
            ),
        )

        assertEquals("car", named(layout, CommandButton.SLOT_BACK))
        assertEquals("headset", named(layout, CommandButton.SLOT_FORWARD))
        assertTrue(layout.map { it.displayName.toString() }.containsAll(listOf("skipBack", "skipForward")))
    }

    /** With no output actions to show, the skips take the bar back — the pre-change layout, unchanged. */
    @Test
    fun `the skips reclaim the bar when no output action is published`() {
        val layout = convert(
            MediaButtonLayout.inPriorityOrder(
                outputActions = emptyList(),
                skipActions = listOf(
                    button("skipBack", CommandButton.SLOT_BACK),
                    button("skipForward", CommandButton.SLOT_FORWARD),
                ),
                overflowActions = emptyList(),
            ),
        )

        assertEquals("skipBack", named(layout, CommandButton.SLOT_BACK))
        assertEquals("skipForward", named(layout, CommandButton.SLOT_FORWARD))
    }

    /** The anti-restart invariant, asserted against the app's own ordering rather than a synthetic list. */
    @Test
    fun `the back slot is occupied whichever output actions are published`() {
        listOf(
            listOf(button("car", CommandButton.SLOT_BACK), button("headset", CommandButton.SLOT_FORWARD)),
            listOf(button("car", CommandButton.SLOT_BACK)),
            listOf(button("headset", CommandButton.SLOT_FORWARD)),
            emptyList(),
        ).forEach { outputs ->
            val layout = convert(
                MediaButtonLayout.inPriorityOrder(
                    outputActions = outputs,
                    skipActions = listOf(
                        button("skipBack", CommandButton.SLOT_BACK),
                        button("skipForward", CommandButton.SLOT_FORWARD),
                    ),
                    overflowActions = emptyList(),
                ),
            )

            assertTrue(
                CommandButton.containsButtonForSlot(layout, CommandButton.SLOT_BACK),
                "outputs=${outputs.map { it.displayName }} left the back slot empty, re-arming the restart bug",
            )
        }
    }

    private fun named(layout: List<CommandButton>, slot: Int): String =
        layout.first { it.slots.asList().single() == slot }.displayName.toString()

    private fun convert(buttons: List<CommandButton>) =
        CommandButton.getCustomLayoutFromMediaButtonPreferences(buttons, true, true, SESSION_INTERFACE_VERSION)

    private fun button(action: String, slot: Int): CommandButton =
        base(action).setSlots(slot, CommandButton.SLOT_OVERFLOW).build()

    private fun overflow(action: String): CommandButton = base(action).setSlots(CommandButton.SLOT_OVERFLOW).build()

    private fun base(action: String): CommandButton.Builder = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
        .setDisplayName(action)
        .setSessionCommand(SessionCommand(action, Bundle.EMPTY))
        .setEnabled(true)

    private companion object {
        const val SESSION_INTERFACE_VERSION = 10
    }
}
