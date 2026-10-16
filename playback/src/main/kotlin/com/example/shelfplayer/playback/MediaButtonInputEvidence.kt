package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput

/**
 * MediaSession tells us which media key was pressed, but not necessarily which Bluetooth
 * headset transmitted it. The KeyEvent may originate from a virtual input device.
 *
 * Matching InputDevice.name against an audio output display name is only a diagnostic
 * clue, never proof that the button and the audio sink are the same physical device.
 * In particular, do not silently switch from headset A to B based on this evidence.
 */
internal object MediaButtonInputEvidence {
    enum class Origin {
        MissingEvent,
        VirtualOrSystem,
        UnresolvedInput,
        ExternalNameUnmatched,
        ExternalNameAmbiguous,
        ExternalNameCandidate,
    }

    data class Finding(val origin: Origin, val headsetNameMatches: Int)

    fun classify(
        deviceId: Int?,
        isVirtual: Boolean?,
        inputName: String?,
        outputs: List<AudioOutput>,
    ): Finding {
        if (deviceId == null) return Finding(Origin.MissingEvent, 0)
        if (deviceId <= 0 || isVirtual == true) return Finding(Origin.VirtualOrSystem, 0)
        if (isVirtual == null || inputName.isNullOrBlank()) return Finding(Origin.UnresolvedInput, 0)

        val name = inputName.trim()
        val matches = outputs.count { output ->
            output.isHeadsetCandidate && output.displayName.equals(name, ignoreCase = true)
        }
        val kind = when (matches) {
            0 -> Origin.ExternalNameUnmatched
            1 -> Origin.ExternalNameCandidate
            else -> Origin.ExternalNameAmbiguous
        }
        return Finding(kind, matches)
    }
}
