package androidx.media3.session

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.example.shelfplayer.core.model.playback.SleepTimerMode
import com.example.shelfplayer.core.model.playback.SleepTimerState
import com.example.shelfplayer.playback.MediaButtonLayout
import com.example.shelfplayer.playback.NotificationButtons
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * PRODUCT_SPEC PLAY-002 / PLAY-007 — BookWave's state-dependent button order, run through the exact
 * package-private conversion Android Auto's legacy MediaSession path uses.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class MediaButtonLayoutTest {

    @Test
    fun `the phone keeps skips first when no car controller is bound`() {
        val ordered = ordered(
            outputs = listOf(
                button("car", CommandButton.SLOT_BACK),
                button("headset", CommandButton.SLOT_FORWARD),
            ),
            carBound = false,
        )

        assertEquals(
            listOf("skipBack", "skipForward", "car", "headset", "sleep"),
            ordered.map { it.displayName.toString() },
        )
        val layout = convert(ordered)
        assertEquals("skipBack", named(layout, CommandButton.SLOT_BACK))
        assertEquals("skipForward", named(layout, CommandButton.SLOT_FORWARD))
        assertTrue(layout.map { it.displayName.toString() }.containsAll(listOf("car", "headset")))
    }

    @Test
    fun `notification timer projection is absent when idle and carries authoritative remaining state when active`() {
        assertNull(NotificationButtons.sleepTimerButton(SleepTimerState.Idle) { "unused" })

        val twelve = requireNotNull(
            NotificationButtons.sleepTimerButton(
                SleepTimerState(
                    mode = SleepTimerMode.Fixed(30.minutes),
                    remaining = 12.minutes,
                    isFading = false,
                ),
            ) { remaining -> "${remaining.inWholeMinutes}m" },
        )
        val nine = requireNotNull(
            NotificationButtons.sleepTimerButton(
                SleepTimerState(
                    mode = SleepTimerMode.Fixed(30.minutes),
                    remaining = 9.minutes,
                    isFading = false,
                ),
            ) { remaining -> "${remaining.inWholeMinutes}m" },
        )

        assertEquals("12m", twelve.displayName.toString())
        assertTrue(twelve.iconResId != 0, "compact state needs a visible sleep-timer glyph")
        assertEquals("9m", nine.displayName.toString(), "extension/ticks must republish from the new owner state")
        assertEquals(
            listOf(CommandButton.SLOT_FORWARD, CommandButton.SLOT_OVERFLOW),
            twelve.slots.asList(),
        )
    }

    @Test
    fun `active timer owns the phone forward compact slot while back skip remains essential`() {
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
                activeTimerActions = listOf(button("sleep 12m", CommandButton.SLOT_FORWARD)),
                overflowActions = emptyList(),
                carBound = false,
            ),
        )

        assertEquals("skipBack", named(layout, CommandButton.SLOT_BACK))
        assertEquals("sleep 12m", named(layout, CommandButton.SLOT_FORWARD))
        assertTrue(layout.map { it.displayName.toString() }.contains("skipForward"))
    }

    @Test
    fun `active timer keeps compact forward slot even while car outputs have priority`() {
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
                activeTimerActions = listOf(button("sleep 9m", CommandButton.SLOT_FORWARD)),
                overflowActions = emptyList(),
                carBound = true,
            ),
        )

        assertEquals("car", named(layout, CommandButton.SLOT_BACK))
        assertEquals("sleep 9m", named(layout, CommandButton.SLOT_FORWARD))
        assertTrue(layout.map { it.displayName.toString() }.containsAll(listOf("headset", "skipBack", "skipForward")))
    }

    @Test
    fun `the car gets output actions first while a car controller is bound`() {
        val layout = convert(
            ordered(
                outputs = listOf(
                    button("car", CommandButton.SLOT_BACK),
                    button("headset", CommandButton.SLOT_FORWARD),
                ),
                carBound = true,
            ),
        )

        assertEquals("car", named(layout, CommandButton.SLOT_BACK))
        assertEquals("headset", named(layout, CommandButton.SLOT_FORWARD))
        assertTrue(layout.map { it.displayName.toString() }.containsAll(listOf("skipBack", "skipForward")))
    }

    @Test
    fun `car binding and output visibility matrix keeps both skips available and the back slot occupied`() {
        val outputStates = listOf(
            listOf(button("car", CommandButton.SLOT_BACK), button("headset", CommandButton.SLOT_FORWARD)),
            listOf(button("car", CommandButton.SLOT_BACK)),
            listOf(button("headset", CommandButton.SLOT_FORWARD)),
            emptyList(),
        )

        listOf(false, true).forEach { carBound ->
            outputStates.forEach { outputs ->
                val layout = convert(ordered(outputs, carBound))
                val names = layout.map { it.displayName.toString() }
                val outputNames = outputs.map { it.displayName.toString() }

                assertTrue("skipBack" in names, "carBound=$carBound outputs=$outputNames dropped skipBack")
                assertTrue("skipForward" in names, "carBound=$carBound outputs=$outputNames dropped skipForward")
                assertTrue(
                    CommandButton.containsButtonForSlot(layout, CommandButton.SLOT_BACK),
                    "carBound=$carBound outputs=$outputNames left SLOT_BACK empty and re-armed Previous",
                )

                val expectedBack = if (carBound && "car" in outputNames) "car" else "skipBack"
                val expectedForward = if (carBound && "headset" in outputNames) "headset" else "skipForward"
                assertEquals(expectedBack, named(layout, CommandButton.SLOT_BACK))
                assertEquals(expectedForward, named(layout, CommandButton.SLOT_FORWARD))
            }
        }
    }

    private fun ordered(outputs: List<CommandButton>, carBound: Boolean): List<CommandButton> =
        MediaButtonLayout.inPriorityOrder(
            outputActions = outputs,
            skipActions = listOf(
                button("skipBack", CommandButton.SLOT_BACK),
                button("skipForward", CommandButton.SLOT_FORWARD),
            ),
            overflowActions = listOf(overflow("sleep")),
            carBound = carBound,
        )

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
