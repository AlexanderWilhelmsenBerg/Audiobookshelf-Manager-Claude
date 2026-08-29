package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.DeviceKind

/**
 * PRODUCT_SPEC PLAY-002 — the three answers [AutoLibrary.Outputs] needs, without an `AudioManager`.
 *
 * The whole reason [AutoLibrary.Outputs] is an interface: every decision the browse tree makes about output
 * rows — whether the tab appears, what a row is called, which one is marked, what choosing does — is a
 * decision about lists, and none of it should need a device to exercise. What is genuinely untestable is
 * `ExoPlayer.setPreferredAudioDevice` and `AudioManager.getDevices`, and neither is here.
 *
 * [chosen] records what the tree asked for, so a test can assert that opening a row *selects* rather than
 * merely rendering something.
 */
internal class FakeAutoOutputs(
    private var outputs: List<AudioOutput> = emptyList(),
    private var selectedId: String? = null,
) : AutoLibrary.Outputs {

    /** Every id passed to [select], in order. `null` is *Automatic* and is recorded like any other. */
    val chosen: MutableList<String?> = mutableListOf()

    override fun available(): List<AudioOutput> = outputs.map { it.copy(isActive = it.id == selectedId) }

    override fun selected(): String? = selectedId

    override fun select(id: String?) {
        chosen += id
        // The real router refuses an id it cannot see, and a test asserting the refusal needs the same rule.
        if (id != null && outputs.none { it.id == id }) return
        selectedId = id
    }

    companion object {
        fun of(vararg outputs: AudioOutput, selected: String? = null) = FakeAutoOutputs(outputs.toList(), selected)

        fun output(id: String, name: String, kind: DeviceKind = DeviceKind.Bluetooth) =
            AudioOutput(id = id, displayName = name, kind = kind)
    }
}
