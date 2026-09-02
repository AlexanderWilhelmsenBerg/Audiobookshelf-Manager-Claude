package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.auth.SessionStatus
import com.example.shelfplayer.domain.repository.AuthRepository
import javax.inject.Inject

/**
 * PRODUCT_SPEC AUTH-004 — one attempt to replace an expired access token.
 *
 * The repository owns the actual refresh-token exchange and the durable reauthentication state. This
 * use case deliberately returns only whether the caller earned one retry; it never loops a credential
 * request and never turns a failed renewal into a second renewal.
 */
class RenewProfileSessionUseCase @Inject constructor(private val authRepository: AuthRepository) {
    suspend operator fun invoke(profileId: ProfileId): Boolean =
        when (val renewed = authRepository.renewSession(profileId)) {
            is AppResult.Success -> renewed.value == SessionStatus.Active
            is AppResult.Failure -> false
        }
}
