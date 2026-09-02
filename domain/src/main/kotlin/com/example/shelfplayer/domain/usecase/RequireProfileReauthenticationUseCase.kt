package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.domain.repository.AuthRepository
import javax.inject.Inject

/** Marks a renewed-but-rejected credential unusable without attempting a second renewal. */
class RequireProfileReauthenticationUseCase @Inject constructor(private val authRepository: AuthRepository) {
    suspend operator fun invoke(profileId: ProfileId) {
        authRepository.requireReauthentication(profileId)
    }
}
