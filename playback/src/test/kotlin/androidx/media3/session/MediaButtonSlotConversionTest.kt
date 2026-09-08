package androidx.media3.session

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC PLAY-002 — what a **car** does with `CommandButton` slots, executed rather than asserted.
 *
 * ### Why this test is in Media3's own package
 *
 * `CommandButton.getCustomLayoutFromMediaButtonPreferences` is package-private, and it is the conversion
 * `MediaSessionLegacyStub` runs to turn media button preferences into the legacy custom actions Android
 * Auto draws. Reaching it needs this package name. That is the whole trick, and it buys something specific:
 * the branch's most expensive recurring mistake has been **writing a platform claim into a comment and
 * being wrong** — three findings landed on exactly that. A comment cannot fail. This can.
 *
 * ### The claim under test
 *
 * `BookWave` publishes its output actions with `setSlots(SLOT_BACK_SECONDARY, SLOT_OVERFLOW)` in the belief
 * that a head unit would then draw them in the minimised control bar. A device run said the bar looked
 * unchanged, and this is why: the conversion branches on `SLOT_BACK`, `SLOT_FORWARD` and `SLOT_OVERFLOW`
 * only. The secondary slots are not tested in it at all, so such a button falls through to overflow.
 *
 * If a Media3 upgrade ever starts honouring them, this test fails and the comment in `PlaybackService`
 * that cites it becomes wrong in a way somebody finds out about.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class MediaButtonSlotConversionTest {

    @Test
    fun `a secondary slot request lands in overflow, not the primary bar`() {
        val layout = convert(listOf(custom("car", CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)))

        assertEquals(1, layout.size)
        assertEquals(listOf(CommandButton.SLOT_OVERFLOW), layout.single().slots.asList())
    }

    /** The contrast that proves the conversion does read slots — it just reads only three of them. */
    @Test
    fun `a back slot request does land in the primary bar`() {
        val layout = convert(listOf(custom("skip", CommandButton.SLOT_BACK)))

        assertEquals(listOf(CommandButton.SLOT_BACK), layout.single().slots.asList())
    }

    /**
     * The arrangement BookWave actually publishes: skips holding both primary positions, the output actions
     * asking for the secondary ones. Everything the car can put in its bar is a skip, which is the trade
     * `docs/risks.md` R-109 records.
     */
    @Test
    fun `with skips holding the primary slots the output actions are all in overflow`() {
        val layout = convert(
            listOf(
                custom("skipBack", CommandButton.SLOT_BACK),
                custom("skipForward", CommandButton.SLOT_FORWARD),
                custom("car", CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW),
                custom("headset", CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW),
            ),
        )

        val bySlot = layout.map { button -> button.slots.asList().single() }
        assertEquals(
            listOf(
                CommandButton.SLOT_BACK,
                CommandButton.SLOT_FORWARD,
                CommandButton.SLOT_OVERFLOW,
                CommandButton.SLOT_OVERFLOW,
            ),
            bySlot,
        )
    }

    /** A button that never names overflow is dropped entirely once the primary slots are taken. */
    @Test
    fun `a secondary-only request is dropped rather than relocated`() {
        val layout = convert(
            listOf(
                custom("skipBack", CommandButton.SLOT_BACK),
                custom("car", CommandButton.SLOT_BACK_SECONDARY),
            ),
        )

        assertEquals(listOf(CommandButton.SLOT_BACK), layout.map { it.slots.asList().single() })
    }

    /*
     * The arrangement BookWave publishes after the owner's device run: the output actions lead the list and
     * name the primary slots, the skips follow and accept overflow. "I need them more than seek forward and
     * back." These four cases are the whole state space of showCar/showHeadset.
     */

    @Test
    fun `car and headset take the bar and the skips fall to overflow`() {
        val layout = convert(bookwaveButtons(car = true, headset = true))

        assertEquals(
            listOf("car", "headset", "skipBack", "skipForward", "sleep"),
            layout.map { it.displayName.toString() },
        )
        assertEquals(CommandButton.SLOT_BACK, slotOf(layout, "car"))
        assertEquals(CommandButton.SLOT_FORWARD, slotOf(layout, "headset"))
        assertEquals(CommandButton.SLOT_OVERFLOW, slotOf(layout, "skipBack"))
        assertEquals(CommandButton.SLOT_OVERFLOW, slotOf(layout, "skipForward"))
    }

    /** With no headset connected, skip forward keeps the position Headset would have taken. */
    @Test
    fun `only car shown leaves skip forward in the bar`() {
        val layout = convert(bookwaveButtons(car = true, headset = false))

        assertEquals(CommandButton.SLOT_BACK, slotOf(layout, "car"))
        assertEquals(CommandButton.SLOT_FORWARD, slotOf(layout, "skipForward"))
        assertEquals(CommandButton.SLOT_OVERFLOW, slotOf(layout, "skipBack"))
    }

    @Test
    fun `only headset shown leaves skip back in the bar`() {
        val layout = convert(bookwaveButtons(car = false, headset = true))

        assertEquals(CommandButton.SLOT_BACK, slotOf(layout, "skipBack"))
        assertEquals(CommandButton.SLOT_FORWARD, slotOf(layout, "headset"))
    }

    /** No output actions at all is the pre-change layout, unchanged. */
    @Test
    fun `neither output action shown restores the skips to both primary slots`() {
        val layout = convert(bookwaveButtons(car = false, headset = false))

        assertEquals(CommandButton.SLOT_BACK, slotOf(layout, "skipBack"))
        assertEquals(CommandButton.SLOT_FORWARD, slotOf(layout, "skipForward"))
    }

    /**
     * **The anti-restart invariant, and the reason this file exists rather than a comment.**
     *
     * If nothing holds the back slot, Media3 stops clearing `ACTION_SKIP_TO_PREVIOUS`; this app has no
     * `ForwardingPlayer` intercepting it, so a head unit's *previous* reaches `Player.seekToPrevious` and
     * restarts a thirty-four-hour book. Some button must hold that slot in every state, and after this
     * change which button it is varies — so the property is asserted directly rather than inferred from
     * whichever case a reader happens to look at.
     */
    @Test
    fun `something always holds the back slot, in every state`() {
        listOf(true to true, true to false, false to true, false to false).forEach { (car, headset) ->
            val layout = convert(bookwaveButtons(car = car, headset = headset))

            assertTrue(
                CommandButton.containsButtonForSlot(layout, CommandButton.SLOT_BACK),
                "showCar=$car showHeadset=$headset left the back slot empty, which re-arms the restart bug",
            )
        }
    }

    /** A displaced skip is dropped, not relocated, without its overflow fallback — so it must keep one. */
    @Test
    fun `a skip without the overflow fallback disappears once an output action takes its slot`() {
        val layout = convert(
            listOf(
                custom("car", CommandButton.SLOT_BACK, CommandButton.SLOT_OVERFLOW),
                custom("skipBack", CommandButton.SLOT_BACK),
            ),
        )

        assertEquals(listOf("car"), layout.map { it.displayName.toString() })
    }

    /** BookWave's real list order, mirroring `PlaybackService.mediaButtons`. */
    private fun bookwaveButtons(car: Boolean, headset: Boolean): List<CommandButton> = buildList {
        if (car) add(custom("car", CommandButton.SLOT_BACK, CommandButton.SLOT_OVERFLOW))
        if (headset) add(custom("headset", CommandButton.SLOT_FORWARD, CommandButton.SLOT_OVERFLOW))
        add(custom("skipBack", CommandButton.SLOT_BACK, CommandButton.SLOT_OVERFLOW))
        add(custom("skipForward", CommandButton.SLOT_FORWARD, CommandButton.SLOT_OVERFLOW))
        add(custom("sleep", CommandButton.SLOT_OVERFLOW))
    }

    private fun slotOf(layout: List<CommandButton>, name: String): Int =
        layout.first { it.displayName.toString() == name }.slots.asList().single()

    /**
     * The same arguments `MediaSessionLegacyStub.updateCustomLayoutAndLegacyExtrasForMediaButtonPreferences`
     * passes: both primary slots available, and its session interface version.
     */
    private fun convert(buttons: List<CommandButton>) =
        CommandButton.getCustomLayoutFromMediaButtonPreferences(buttons, true, true, SESSION_INTERFACE_VERSION)

    /**
     * One custom action. The slots are passed positionally rather than as a vararg because Lint cannot
     * check `@IntDef` constants through a spread, and no icon is set because the conversion under test
     * does not read one — `MediaButtonPublishingTest` covers the icon the car actually receives.
     */
    private fun custom(action: String, slot: Int): CommandButton = custom(action).setSlots(slot).build()

    private fun custom(action: String, slot: Int, fallback: Int): CommandButton =
        custom(action).setSlots(slot, fallback).build()

    private fun custom(action: String): CommandButton.Builder = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
        .setDisplayName(action)
        .setSessionCommand(SessionCommand(action, Bundle.EMPTY))
        .setEnabled(true)

    private companion object {
        const val SESSION_INTERFACE_VERSION = 10
    }
}
