package com.example.shelfplayer.domain.realtime

import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.auth.AccountProgress
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One low-latency progress observation that may be useful to a later resume decision.
 *
 * This is an in-memory handoff, not another source of truth. [ObserveRealtimeUpdatesUseCase] still persists
 * the row through the conflict-safe library repository; this stream merely lets the playback layer notice
 * that the push arrived while a particular paused baseline was current. There is deliberately no replay:
 * after process death/background gaps the socket cannot prove it saw every event, so #91 must fall back to
 * REST rather than treating old in-memory news as complete.
 */
data class RealtimeProgressEvidence(val profileId: ProfileId, val progress: AccountProgress, val sessionId: String?)

@Singleton
class RealtimeProgressEvidenceStore @Inject constructor() {
    private val _updates = MutableSharedFlow<RealtimeProgressEvidence>(extraBufferCapacity = BUFFER_CAPACITY)

    val updates: SharedFlow<RealtimeProgressEvidence> = _updates.asSharedFlow()

    /** Best effort by design: persistence already happened separately and REST is the correctness fallback. */
    fun record(profileId: ProfileId, progress: AccountProgress, sessionId: String?) {
        _updates.tryEmit(RealtimeProgressEvidence(profileId, progress, sessionId))
    }

    private companion object {
        const val BUFFER_CAPACITY = 32
    }
}
