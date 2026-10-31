package com.example.shelfplayer.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info

/** No guard is distinct from a guard that cannot safely prepare this Play. */
internal enum class ManualPlayProtection { NoGuard, MutedAllowed, Denied }

/** Observation only. Exports availability flags, never addresses, identity digests or endpoint tokens. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun ManualPlayProtection.reportDenial(logger: Logger, router: AudioOutputRouter, player: Player) {
    if (this != ManualPlayProtection.Denied) return
    val guarded = router.positions.guard.endpointId
    logger.info(
        LogCategory.Playback,
        "Manual Play denied by output protection",
        LogField.Public("playerAttached", router.isAttachedPlayer(player)),
        LogField.Public("guardPresent", guarded != null),
        LogField.Public("guardConnected", guarded in router.localOutputs.endpointIds),
        LogField.Public("guardSelected", guarded != null && router.selectedId.value == guarded),
        LogField.Public("guardCollision", guarded in router.localOutputs.collidingOutputIds),
        LogField.Public("identifiedEndpoints", router.localOutputs.identifiedEndpointCount),
        LogField.Public("destination", router.explicitSelection.value?.destination?.name ?: "Automatic"),
    )
}
