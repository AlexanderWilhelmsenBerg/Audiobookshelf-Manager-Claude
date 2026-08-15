package com.example.shelfplayer.data.auth

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.database.DatabaseTransactionRunner
import com.example.shelfplayer.core.database.dao.ProfileDao
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerCandidate
import com.example.shelfplayer.core.model.asFailure
import com.example.shelfplayer.core.model.auth.AccountState
import com.example.shelfplayer.core.model.auth.AuthSession
import com.example.shelfplayer.core.model.auth.SessionIdentity
import com.example.shelfplayer.core.model.auth.SessionStatus
import com.example.shelfplayer.core.model.flatMap
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.core.network.http.NormalizedServerUrl
import com.example.shelfplayer.core.network.http.ServerUrlNormalizer
import com.example.shelfplayer.domain.repository.AuthRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC AUTH-001 / AUTH-002 — sign-in, profile creation, and the profile's session.
 *
 * The order of operations in [signIn] is the whole requirement, not an implementation detail:
 *
 * 1. normalize the address, so the profile is keyed to a canonical URL;
 * 2. authenticate, so nothing is written for a rejected credential;
 * 3. write the server and profile rows in one transaction;
 * 4. store the token encrypted;
 * 5. select the profile.
 *
 * Steps 3-5 are the ones that must not be reordered. Selecting a profile before its row exists gives
 * every screen an empty state with no explanation, and storing a token before the profile exists leaves
 * a credential nothing can reach or revoke.
 */
@Singleton
class DefaultAuthRepository @Inject constructor(
    private val gateway: AudiobookshelfGateway,
    private val urlNormalizer: ServerUrlNormalizer,
    private val profileDao: ProfileDao,
    private val transaction: DatabaseTransactionRunner,
    private val sessionTokens: SessionTokenProvider,
    private val settings: AppSettingsDataSource,
    private val clock: AppClock,
    private val logger: Logger,
    @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
) : AuthRepository {

    override suspend fun probeServer(serverUrl: String): AppResult<ServerCandidate> = withContext(ioDispatcher) {
        urlNormalizer.normalize(serverUrl).flatMap { normalized ->
            gateway.auth.probe(normalized.value).flatMap { probe ->
                rejectionFor(probe.isAudiobookshelf, probe.isInitialized)?.asFailure()
                    ?: AppResult.Success(
                        ServerCandidate(
                            serverUrl = normalized.value,
                            isCleartext = normalized.isCleartext,
                            wasSchemeAssumed = normalized.wasSchemeAssumed,
                            probe = probe,
                        ),
                    )
            }
        }
    }

    /**
     * Why a probe that answered can still be a failure.
     *
     * A reachable host is not an Audiobookshelf server, and an Audiobookshelf server that has never
     * completed first-run setup has no accounts to sign in to. Both would otherwise surface as an
     * incorrect password, which sends the user to fix the one thing that is not wrong
     * (PRODUCT_SPEC 14.4).
     */
    private fun rejectionFor(isAudiobookshelf: Boolean, isInitialized: Boolean): AppError? = when {
        !isAudiobookshelf -> AppError.ApiCompatibility(
            summary = "That address answered, but it is not an Audiobookshelf server.",
            missingField = "app",
        )

        !isInitialized -> AppError.Validation(
            summary = "This Audiobookshelf server has not finished its first-time setup yet.",
        )

        else -> null
    }

    override suspend fun signIn(serverUrl: String, username: String, password: String): AppResult<Profile> =
        withContext(ioDispatcher) {
            urlNormalizer.normalize(serverUrl).flatMap { normalized ->
                gateway.auth.signIn(normalized.value, username, password).flatMap { session ->
                    // Reached only for an accepted credential, so nothing below can leave a profile
                    // behind for a failed sign-in (PRODUCT_SPEC AUTH-001).
                    AppResult.Success(persist(normalized, session))
                }
            }
        }

    /**
     * Writes the profile and takes custody of the credential.
     *
     * The transaction covers both rows because a profile without its server violates a foreign key and
     * a server without its profile is invisible. It deliberately does *not* cover the token write or the
     * active-profile selection: neither is a database operation, and holding a transaction open across a
     * Keystore encryption and a DataStore write would block every other writer on file I/O.
     *
     * The consequence is that a crash between the transaction and the token write leaves a profile with
     * no session. That state is already modelled — it is exactly a profile requiring reauthentication —
     * so it degrades to a sign-in prompt rather than to corruption.
     */
    private suspend fun persist(normalized: NormalizedServerUrl, session: AuthSession): Profile {
        val serverId = SessionIdentity.serverIdFor(normalized.value)
        val profileId = SessionIdentity.profileIdFor(serverId, session.userId, session.username)
        val now = clock.now()
        val profile = Profile(
            id = profileId,
            serverId = serverId,
            username = session.username,
            displayName = session.username,
            role = session.role,
            requiresReauthentication = false,
            lastUsedAt = now,
            isFixture = false,
            canDownload = session.access.canDownload,
        )

        transaction {
            val existing = profileDao.findServer(serverId.value)
            if (existing == null) {
                // No probe result is threaded in from the caller, so the version and authentication
                // modes stay empty until the capability handshake writes them (PRODUCT_SPEC SYNC-001).
                // Copying them from a probe the UI happened to run would make the row's contents depend
                // on which screen created it.
                profileDao.upsertServer(
                    AuthEntityMappers.newServerEntity(
                        serverId = serverId,
                        baseUrl = normalized.value,
                        probe = null,
                        fetchedAt = now,
                    ),
                )
            } else {
                // An existing row keeps its capability handshake; only identity and probe results are
                // refreshed. See ProfileDao.updateServerIdentity.
                profileDao.updateServerIdentity(
                    serverId = serverId.value,
                    displayName = AuthEntityMappers.displayNameFor(normalized.value),
                    baseUrl = normalized.value,
                    detectedVersion = existing.detectedVersion,
                    authMethodsJson = existing.authMethodsJson,
                    fetchedAt = now.toEpochMilli(),
                )
            }
            // The grant is written with the profile, not after it: PRODUCT_SPEC 5.2 has the sync apply it,
            // and a profile that exists for even a moment without one would sync with `LibraryAccess.None`
            // and soft-delete every cached book in the process.
            profileDao.upsertProfile(
                AuthEntityMappers.toEntity(profile, session.userId, session.access, session.accountType),
            )
        }

        sessionTokens.adopt(profileId, session)
        settings.setActiveProfile(profileId)

        logger.info(
            LogCategory.Auth,
            "Signed in and stored the profile",
            LogField.Identifier("profile", profileId.value),
            LogField.Public("renewable", session.isRenewable),
            LogField.Public("role", session.role.name),
        )
        return profile
    }

    override suspend fun restoreSession(profileId: ProfileId): AppResult<SessionStatus> = withContext(ioDispatcher) {
        val profile = profileDao.findProfile(profileId.value)
            ?: return@withContext AppError.Validation(
                summary = "That profile is no longer saved on this device.",
            ).asFailure()

        if (sessionTokens.activate(profileId)) {
            // A profile previously marked for reauthentication can become usable again: the user may
            // have signed in on another screen, or a renewal may have succeeded. Clearing the mark here
            // means the flag reflects the credential rather than the last thing that went wrong.
            if (profile.requiresReauthentication) {
                profileDao.setRequiresReauthentication(profileId.value, required = false)
            }
            profileDao.setLastUsedAt(profileId.value, clock.now().toEpochMilli())
            AppResult.Success(SessionStatus.Active)
        } else {
            markReauthenticationRequired(profileId, reason = "no usable stored credential")
            AppResult.Success(SessionStatus.ReauthenticationRequired)
        }
    }

    /**
     * PRODUCT_SPEC AUTH-004 — one renewal attempt, and never a silent sign-out.
     *
     * Three different failures land in the same place, and collapsing them is the point: no stored
     * refresh token, a server that refused it, and a refreshed session that came back unusable all mean
     * "the user has to sign in again", and all three keep the profile, its downloads and its local
     * progress exactly where they are.
     *
     * The renewed session replaces both tokens. Audiobookshelf issues a new refresh token with each
     * renewal, so keeping the old one would work once and then fail at the following renewal — hours
     * later, on a device, in a way that looks like a random sign-out.
     */
    override suspend fun renewSession(profileId: ProfileId): AppResult<SessionStatus> = withContext(ioDispatcher) {
        val baseUrl = serverBaseUrlFor(profileId)
            ?: return@withContext AppError.Validation(
                summary = "That profile is no longer saved on this device.",
            ).asFailure()

        val refreshToken = sessionTokens.refreshTokenFor(profileId)
        if (refreshToken == null) {
            // The session was never renewable: the server withheld the refresh token, which it does
            // unless the login carried `x-return-tokens: true`. See AuthSession.isRenewable.
            markReauthenticationRequired(profileId, reason = "session is not renewable")
            return@withContext AppResult.Success(SessionStatus.ReauthenticationRequired)
        }

        when (val renewed = gateway.auth.refresh(baseUrl, refreshToken)) {
            is AppResult.Failure -> {
                logger.info(
                    LogCategory.Auth,
                    "Session renewal was refused",
                    LogField.Identifier("profile", profileId.value),
                    LogField.Public("errorCode", renewed.error.code),
                )
                markReauthenticationRequired(profileId, reason = "renewal refused")
                AppResult.Success(SessionStatus.ReauthenticationRequired)
            }

            is AppResult.Success -> {
                sessionTokens.adopt(profileId, renewed.value)
                profileDao.setRequiresReauthentication(profileId.value, required = false)
                // A renewal is also a fresh statement of the account's permissions, so the stored grant is
                // updated from it. PRODUCT_SPEC 5.2 wants the grant refreshed rather than assumed
                // unchanged: a library revoked while the session was expired must not come back with it.
                profileDao.setAccountState(
                    profileId = profileId.value,
                    accessibleLibrariesJson = AuthEntityMappers.accessibleLibrariesJson(renewed.value.access),
                    hasAllLibraryAccess = renewed.value.access.hasAllLibraryAccess,
                    hasAllTagAccess = renewed.value.access.hasAllTagAccess,
                    canDownload = renewed.value.access.canDownload,
                    role = renewed.value.role.name,
                )
                profileDao.setManagementPermissions(
                    profileId = profileId.value,
                    canUpdate = renewed.value.access.canUpdate,
                    canDelete = renewed.value.access.canDelete,
                    canUpload = renewed.value.access.canUpload,
                    accountType = renewed.value.accountType,
                )
                logger.info(
                    LogCategory.Auth,
                    "Renewed a session without re-prompting",
                    LogField.Identifier("profile", profileId.value),
                    LogField.Public("renewable", renewed.value.isRenewable),
                )
                AppResult.Success(SessionStatus.Active)
            }
        }
    }

    /**
     * PRODUCT_SPEC 5.2 — asks the server what this account may do now, and records the answer.
     *
     * ### What each outcome means, and why they differ
     *
     * A **success** overwrites the stored grant and the role. It also clears the reauthentication mark:
     * the token just proved it works, which is stronger evidence than whatever set the mark.
     *
     * An **authentication failure** is the token no longer being accepted — revoked, expired, or the
     * account deleted. AUTH-004 wants the profile marked, and the stored grant left alone: a `401` says
     * nothing about what the account may see, and blanking the grant would hide cached content the user
     * is entitled to browse offline.
     *
     * An **authorization failure** is [AuthMapper.toAccountState]'s disabled-or-locked check. A device run
     * found this one from the other side: disabling a user on the server did lock them out, while changing
     * their password did not, because Audiobookshelf does not invalidate tokens on a password change. This
     * is the call that notices either.
     *
     * Anything **else** — a timeout, a 5xx, an unreachable host — changes nothing. Offline is not a
     * permission change, and treating it as one would revoke a user's library every time they walked into
     * a lift.
     */
    override suspend fun refreshPermissions(profileId: ProfileId): AppResult<AccountState> = withContext(ioDispatcher) {
        val baseUrl = serverBaseUrlFor(profileId)
            ?: return@withContext AppError.Validation(
                summary = "That profile is no longer saved on this device.",
            ).asFailure()
        val token = sessionTokens.accessTokenFor(profileId)
            ?: return@withContext AppError.Authentication().asFailure()

        when (val account = gateway.auth.currentAccount(baseUrl, token)) {
            is AppResult.Failure -> {
                // Only an *authorization* failure marks the profile here, and the distinction is the
                // whole of AUTH-004.
                //
                // A `401` means the access token is not being accepted, which is the ordinary end of a
                // token's life and is exactly what the refresh token exists for. Marking on it would
                // announce "you have been signed out" to a user whose session could have been renewed
                // silently — which is what happened: a device run found a working profile reported as
                // signed out simply because the app had started asking this question on every resume.
                // The renewal belongs to the caller (`SyncAccountUseCase`), which retries once and lets
                // `renewSession` do the marking if that fails too.
                //
                // A `403`, or the disabled/locked account `AuthMapper.toAccountState` reports as one, is
                // different in kind: no renewal changes it, so the mark is the correct and only answer.
                if (account.error is AppError.Authorization) {
                    markReauthenticationRequired(profileId, reason = "the account is no longer permitted")
                }
                AppResult.Failure(account.error)
            }

            is AppResult.Success -> {
                val access = account.value.access
                profileDao.setAccountState(
                    profileId = profileId.value,
                    accessibleLibrariesJson = AuthEntityMappers.accessibleLibrariesJson(access),
                    hasAllLibraryAccess = access.hasAllLibraryAccess,
                    hasAllTagAccess = access.hasAllTagAccess,
                    canDownload = access.canDownload,
                    role = account.value.role.name,
                )
                profileDao.setManagementPermissions(
                    profileId = profileId.value,
                    canUpdate = access.canUpdate,
                    canDelete = access.canDelete,
                    canUpload = access.canUpload,
                    accountType = account.value.accountType,
                )
                profileDao.setRequiresReauthentication(profileId.value, required = false)
                logger.info(
                    LogCategory.Auth,
                    "Refreshed an account's permissions",
                    LogField.Identifier("profile", profileId.value),
                    LogField.Public("allLibraries", access.hasAllLibraryAccess),
                    LogField.Public("allTags", access.hasAllTagAccess),
                    LogField.Count("grantedLibraries", access.accessibleLibraryIds.size),
                )
                AppResult.Success(account.value)
            }
        }
    }

    override suspend fun signOut(profileId: ProfileId): AppResult<Unit> = withContext(ioDispatcher) {
        // Told to the server first, while the credential still exists to authenticate the request. A
        // failure is logged and ignored: the local session goes either way, because a user who asked to
        // sign out must not stay signed in because their server was unreachable.
        tellServer(profileId)
        sessionTokens.clear(profileId)
        markReauthenticationRequired(profileId, reason = "signed out")
        AppResult.Success(Unit)
    }

    private suspend fun tellServer(profileId: ProfileId) {
        val baseUrl = serverBaseUrlFor(profileId) ?: return
        val token = sessionTokens.accessTokenFor(profileId) ?: return
        val result = gateway.auth.signOut(baseUrl, token)
        if (result is AppResult.Failure) {
            logger.warn(
                LogCategory.Auth,
                "The server did not confirm the sign-out; clearing the local session anyway",
                LogField.Public("errorCode", result.error.code),
            )
        }
    }

    override suspend fun removeProfile(profileId: ProfileId): AppResult<Unit> = withContext(ioDispatcher) {
        tellServer(profileId)
        // The credential is destroyed before the row, so an interrupted removal cannot leave a token
        // whose profile is gone: nothing would then know it existed to delete it (PRODUCT_SPEC 15).
        sessionTokens.clear(profileId)
        profileDao.deleteProfile(profileId.value)
        if (settings.activeProfileId.first() == profileId) settings.clearActiveProfile()
        logger.info(
            LogCategory.Auth,
            "Removed a profile and its credential",
            LogField.Identifier("profile", profileId.value),
        )
        AppResult.Success(Unit)
    }

    private suspend fun markReauthenticationRequired(profileId: ProfileId, reason: String) {
        profileDao.setRequiresReauthentication(profileId.value, required = true)
        logger.info(
            LogCategory.Auth,
            "Marked a profile as requiring reauthentication",
            LogField.Identifier("profile", profileId.value),
            // A constant, chosen from a closed set at the call site, so it is safe to log verbatim.
            LogField.Public("reason", reason),
        )
    }

    private suspend fun serverBaseUrlFor(profileId: ProfileId): String? {
        val profile = profileDao.findProfile(profileId.value) ?: return null
        return profileDao.findServer(profile.serverId)?.baseUrl
    }
}
