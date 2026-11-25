package com.example.shelfplayer.playback

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info

/** Privacy-safe formatting only; probe and transaction owners supply all evidence. */
internal object MutedRouteDiagnostics {
    fun unverified(logger: Logger, actualRoute: Set<String>?, guarded: String) {
        val route = when {
            actualRoute.isNullOrEmpty() -> "unknown"
            guarded !in actualRoute -> "other"
            else -> "multiple"
        }
        logger.info(
            LogCategory.Playback,
            "Car continuity paused because the headset route is not verified",
            LogField.Public("route", route),
        )
    }

    fun observation(logger: Logger, probe: HeadsetRouteGuard.Probe, ordinal: Int) {
        logger.info(
            LogCategory.Playback,
            "Muted output route observation requested",
            LogField.Public("proofEpoch", probe.preferenceEpoch),
            LogField.Public("observation", ordinal),
            LogField.Public("completionPending", probe.completionJob != null),
        )
    }

    fun settled(logger: Logger, probe: HeadsetRouteGuard.Probe, proof: MutedProbeCompletion) {
        logger.info(
            LogCategory.Playback,
            "Muted output route proof completed",
            LogField.Public("confirmation", proof.name),
            LogField.Public("destination", probe.transferRequest?.selection?.destination?.name ?: "HeldOutput"),
            LogField.Public("proofEpoch", probe.preferenceEpoch),
            LogField.Public("requestGeneration", probe.transferRequest?.bookGeneration?.toString() ?: "none"),
            LogField.Public("requestSelection", probe.transferRequest?.selection?.sequence?.toString() ?: "none"),
        )
    }
}
