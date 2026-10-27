package com.example.shelfplayer.playback

import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.auth.SessionStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * AUTH-005 / PD-001 / #174 — serializes profile-row and custom-command switches at the car boundary.
 * The domain switch remains the lock, pause, flush and account owner. Re-selecting an active profile
 * does nothing; a successful unlocked switch schedules only the existing silent courtesy restore.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class CarProfileSwitcher(
    private val isActive: suspend (ProfileId) -> Boolean,
    private val switch: suspend (ProfileId) -> AppResult<SessionStatus>,
    private val restore: (ProfileId) -> Unit,
    private val unavailableMessage: () -> String,
    private val unlockMessage: () -> String,
    private val failedMessage: () -> String,
) {
    private val gate = Mutex()

    suspend fun select(profileId: ProfileId?): SessionResult {
        if (profileId == null) {
            return SessionResult(SessionError(SessionError.ERROR_BAD_VALUE, unavailableMessage()))
        }
        return gate.withLock {
            if (isActive(profileId)) SessionResult(SessionResult.RESULT_SUCCESS) else switchTo(profileId)
        }
    }

    private suspend fun switchTo(profileId: ProfileId): SessionResult = when (val result = switch(profileId)) {
        is AppResult.Success -> {
            if (result.value == SessionStatus.Active) restore(profileId)
            SessionResult(SessionResult.RESULT_SUCCESS)
        }

        is AppResult.Failure -> {
            val locked = result.error is AppError.Security
            SessionResult(
                SessionError(
                    if (locked) SessionError.ERROR_PERMISSION_DENIED else SessionError.ERROR_UNKNOWN,
                    if (locked) unlockMessage() else failedMessage(),
                ),
            )
        }
    }
}
