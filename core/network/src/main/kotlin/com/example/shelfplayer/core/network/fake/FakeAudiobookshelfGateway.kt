package com.example.shelfplayer.core.network.fake

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.ServerCapabilities
import com.example.shelfplayer.core.model.flatMap
import com.example.shelfplayer.core.model.library.BookSnapshot
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.map
import com.example.shelfplayer.core.network.fixture.FixtureLibraryLoader
import com.example.shelfplayer.core.network.fixture.FixtureMapper
import com.example.shelfplayer.core.network.gateway.AccountApi
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.core.network.gateway.CapabilityResolver
import com.example.shelfplayer.core.network.gateway.LibraryApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC 20, Phase 0 — "the app opens a fake library, no real credentials needed".
 *
 * A gateway backed entirely by the bundled fixture document. It exists so the whole vertical slice —
 * gateway, repository, Room, UI — can be built and tested before a single server endpoint is
 * contract-tested, and so that CI never needs a server.
 *
 * It behaves like a real gateway in the ways that matter:
 *  - it returns [AppResult], including typed failures for a malformed fixture;
 *  - it enforces the profile boundary rather than ignoring it (PRODUCT_SPEC 5.2);
 *  - it does its work off the main thread through an injected dispatcher.
 *
 * It is replaced, not extended, by the Retrofit-backed gateway in Phase 1.
 */
@Singleton
class FakeAudiobookshelfGateway @Inject constructor(
    private val loader: FixtureLibraryLoader,
    private val clock: AppClock,
    private val logger: Logger,
    @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
) : AudiobookshelfGateway,
    AccountApi,
    CapabilityResolver,
    LibraryApi {
    override val capabilities: CapabilityResolver get() = this
    override val account: AccountApi get() = this
    override val library: LibraryApi get() = this

    override suspend fun resolve(): AppResult<ServerCapabilities> = withMapper { mapper -> mapper.capabilities() }

    override suspend fun currentServer(): AppResult<Server> = withMapper { mapper -> mapper.server() }

    override suspend fun currentProfile(): AppResult<Profile> = withMapper { mapper -> mapper.profile(clock.now()) }

    override suspend fun listLibraries(profileId: ProfileId): AppResult<List<Library>> =
        requireProfile(profileId).flatMap {
            withMapper { mapper -> mapper.libraries(clock.now()) }
        }

    override suspend fun listBooks(profileId: ProfileId, libraryId: LibraryId): AppResult<List<BookSnapshot>> =
        requireProfile(profileId).flatMap {
            withMapper { mapper -> mapper.books(libraryId, clock.now()) }
        }

    /**
     * PRODUCT_SPEC 5.2 — a gateway call for a profile this connection does not serve is a failure,
     * not an empty list. Returning empty would render as "this library is empty" instead of "you are
     * looking at the wrong account".
     */
    private suspend fun requireProfile(profileId: ProfileId): AppResult<Unit> =
        withMapper { mapper -> mapper.profileId }.flatMap { fixtureProfileId ->
            if (fixtureProfileId == profileId) {
                AppResult.Success(Unit)
            } else {
                logger.info(
                    LogCategory.Network,
                    "Rejected fixture gateway call for a non-fixture profile",
                    LogField.Identifier("requestedProfile", profileId.value),
                )
                AppResult.Failure(
                    AppError.Authorization(
                        summary = "This profile is not connected to the demo library.",
                        missingPermission = "fixture.profile",
                    ),
                )
            }
        }

    private suspend fun <T> withMapper(block: (FixtureMapper) -> T): AppResult<T> = withContext(ioDispatcher) {
        loader.load().map { document -> block(FixtureMapper(document)) }
    }
}
