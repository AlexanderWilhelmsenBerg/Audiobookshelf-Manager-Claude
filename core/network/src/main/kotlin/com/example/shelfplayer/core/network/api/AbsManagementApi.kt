package com.example.shelfplayer.core.network.api

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.NewServerUser
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerUser
import com.example.shelfplayer.core.model.asFailure
import com.example.shelfplayer.core.model.library.BookMetadataEdit
import com.example.shelfplayer.core.model.library.BookMetadataField
import com.example.shelfplayer.core.model.library.BookSnapshot
import com.example.shelfplayer.core.model.library.MatchCandidate
import com.example.shelfplayer.core.model.library.MetadataProvider
import com.example.shelfplayer.core.model.library.SeriesEdit
import com.example.shelfplayer.core.model.resultOf
import com.example.shelfplayer.core.network.gateway.CoverUpload
import com.example.shelfplayer.core.network.gateway.ItemScanOutcome
import com.example.shelfplayer.core.network.gateway.LibraryApi
import com.example.shelfplayer.core.network.gateway.ManagementApi
import com.example.shelfplayer.core.network.gateway.ProfileConnection
import com.example.shelfplayer.core.network.gateway.ProfileConnectionResolver
import com.example.shelfplayer.core.network.http.NetworkErrorMapper
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC EPIC MGR — the real [ManagementApi], against contracts captured on 2.36.0.
 *
 * ### No retries
 *
 * Every other API in this package retries a failed read. This one does not, and the difference is the
 * point: a retry is safe when the request is idempotent, and *this* `PATCH` is only idempotent in the
 * arithmetic sense. A request that times out may already have been applied, so a retry would write the
 * same values a second time — harmless — but it would also bump the item's `updatedAt` again, broadcast a
 * second change to every connected client, and rewrite the metadata file on the user's disk.
 *
 * More to the point, a failed save that the user is told about is a save they can retry deliberately,
 * with their draft intact (MGR-001). A failed save the app retries silently is a save nobody can reason
 * about.
 */
@Singleton
internal class AbsManagementApi @Inject constructor(
    private val services: AudiobookshelfServiceFactory,
    private val connections: ProfileConnectionResolver,
    private val library: LibraryApi,
    private val errors: NetworkErrorMapper,
    private val logger: Logger,
) : ManagementApi {

    override suspend fun updateMetadata(
        profileId: ProfileId,
        bookId: LibraryItemId,
        edit: BookMetadataEdit,
        changed: Set<BookMetadataField>,
    ): AppResult<BookSnapshot> {
        // An empty `PATCH` is not a cheap no-op on this endpoint: the server still saves the item, still
        // touches `updatedAt`, still rewrites the metadata file and still broadcasts to every connected
        // client. A save with nothing to save must not leave the wire.
        if (changed.isEmpty()) {
            return AppError.Validation(summary = "There is nothing to save.").asFailure()
        }
        val connection = connections.connectionFor(profileId)
            ?: return AppResult.Failure(AppError.Authentication())

        return when (val sent = send(connection, bookId, edit, changed)) {
            is AppResult.Failure -> AppResult.Failure(sent.error)
            is AppResult.Success -> {
                // The count is what is logged, never which fields or what they now say. A field *name*
                // describes the software; the values are the user's library (PRODUCT_SPEC 14.5).
                logger.info(
                    LogCategory.Sync,
                    "Saved metadata changes to the server",
                    LogField.Count("fields", changed.size),
                    LogField.Public("changedAnything", sent.value.toString()),
                )
                library.fetchBook(profileId, bookId)
            }
        }
    }

    /** `true` when the server reported that the request actually changed something. */
    private suspend fun send(
        connection: ProfileConnection,
        bookId: LibraryItemId,
        edit: BookMetadataEdit,
        changed: Set<BookMetadataField>,
    ): AppResult<Boolean> {
        val transport = resultOf(onError = errors::fromThrowable) {
            services.managementService(connection.serverUrl).updateMedia(
                bearer = bearerOf(connection.accessToken.value),
                itemId = bookId.value,
                body = MetadataPayload.of(edit, changed),
            )
        }
        return when (transport) {
            is AppResult.Failure -> AppResult.Failure(transport.error)
            is AppResult.Success -> {
                val response = transport.value
                if (response.isSuccessful) {
                    AppResult.Success(response.body()?.updated == true)
                } else {
                    AppResult.Failure(errors.fromStatus(response.code()))
                }
            }
        }
    }

    override suspend fun uploadCover(
        profileId: ProfileId,
        bookId: LibraryItemId,
        image: CoverUpload,
    ): AppResult<BookSnapshot> {
        val connection = connections.connectionFor(profileId)
            ?: return AppResult.Failure(AppError.Authentication())

        return when (val sent = send(connection, bookId, image)) {
            is AppResult.Failure -> AppResult.Failure(sent.error)
            is AppResult.Success -> {
                // The byte count describes the request, not the image's contents (PRODUCT_SPEC 14.5).
                logger.info(LogCategory.Sync, "Uploaded a new cover", LogField.Count("bytes", image.bytes.size))
                library.fetchBook(profileId, bookId)
            }
        }
    }

    private suspend fun send(
        connection: ProfileConnection,
        bookId: LibraryItemId,
        image: CoverUpload,
    ): AppResult<Unit> {
        val transport = resultOf(onError = errors::fromThrowable) {
            services.managementService(connection.serverUrl).uploadCover(
                bearer = bearerOf(connection.accessToken.value),
                itemId = bookId.value,
                cover = coverPartOf(image),
            )
        }
        return when (transport) {
            is AppResult.Failure -> AppResult.Failure(transport.error)
            is AppResult.Success -> {
                val response = transport.value
                when {
                    !response.isSuccessful -> AppResult.Failure(errors.fromStatus(response.code()))
                    // A `200` whose body says `success: false` has never been observed, and reporting it as
                    // a failure is the safe reading: an upload the user cannot see is better reported than
                    // assumed.
                    response.body()?.success != true -> AppError.ApiCompatibility(
                        summary = "The server accepted the image but did not confirm it was stored.",
                        missingField = "success",
                    ).asFailure()
                    else -> AppResult.Success(Unit)
                }
            }
        }
    }

    override suspend fun removeCover(profileId: ProfileId, bookId: LibraryItemId): AppResult<BookSnapshot> {
        val connection = connections.connectionFor(profileId)
            ?: return AppResult.Failure(AppError.Authentication())

        val transport = resultOf(onError = errors::fromThrowable) {
            services.managementService(connection.serverUrl).removeCover(
                bearer = bearerOf(connection.accessToken.value),
                itemId = bookId.value,
            )
        }
        return when (transport) {
            is AppResult.Failure -> AppResult.Failure(transport.error)
            is AppResult.Success -> {
                // The body is `text/plain "OK"` and is deliberately not read. The status is the answer;
                // parsing the word would invent a contract the server does not offer.
                transport.value.body()?.close()
                if (transport.value.isSuccessful) {
                    library.fetchBook(profileId, bookId)
                } else {
                    AppResult.Failure(errors.fromStatus(transport.value.code()))
                }
            }
        }
    }

    override suspend fun findCandidates(
        profileId: ProfileId,
        provider: String,
        title: String,
        author: String,
    ): AppResult<List<MatchCandidate>> {
        val connection = connections.connectionFor(profileId)
            ?: return AppResult.Failure(AppError.Authentication())

        val transport = resultOf(onError = errors::fromThrowable) {
            services.managementService(connection.serverUrl).searchBooks(
                bearer = bearerOf(connection.accessToken.value),
                title = title,
                author = author,
                provider = provider,
            )
        }
        return when (transport) {
            is AppResult.Failure -> AppResult.Failure(transport.error)
            is AppResult.Success -> {
                val response = transport.value
                if (response.isSuccessful) {
                    // A candidate with no title cannot be shown or chosen, so it is dropped rather than
                    // rendered as a blank row. Every other field is genuinely optional.
                    AppResult.Success(response.body().orEmpty().mapNotNull { dto -> candidateOf(provider, dto) })
                } else {
                    AppResult.Failure(errors.fromStatus(response.code()))
                }
            }
        }
    }

    override suspend fun metadataProviders(profileId: ProfileId): AppResult<List<MetadataProvider>> {
        val connection = connections.connectionFor(profileId)
            ?: return AppResult.Failure(AppError.Authentication())

        val transport = resultOf(onError = errors::fromThrowable) {
            services.authService(connection.serverUrl)
                .metadataProviders(bearerOf(connection.accessToken.value))
        }
        return when (transport) {
            is AppResult.Failure -> AppResult.Failure(transport.error)
            is AppResult.Success -> {
                val response = transport.value
                if (response.isSuccessful) {
                    AppResult.Success(
                        response.body()?.providers?.books.orEmpty().mapNotNull { dto ->
                            // A provider with no `value` cannot be sent, so it is dropped rather than
                            // offered as a choice that would produce an empty search.
                            dto.value?.takeIf(String::isNotBlank)?.let { id ->
                                MetadataProvider(id, dto.text?.takeIf(String::isNotBlank) ?: id)
                            }
                        },
                    )
                } else {
                    AppResult.Failure(errors.fromStatus(response.code()))
                }
            }
        }
    }

    override suspend fun scanItem(profileId: ProfileId, bookId: LibraryItemId): AppResult<ItemScanOutcome> {
        val connection = connections.connectionFor(profileId)
            ?: return AppResult.Failure(AppError.Authentication())

        return when (val scanned = requestScan(connection, bookId)) {
            is AppResult.Failure -> AppResult.Failure(scanned.error)
            is AppResult.Success -> outcomeOf(profileId, bookId, scanned.value)
        }
    }

    private suspend fun requestScan(connection: ProfileConnection, bookId: LibraryItemId): AppResult<String> {
        val transport = resultOf(onError = errors::fromThrowable) {
            services.managementService(connection.serverUrl)
                .scanItem(bearerOf(connection.accessToken.value), bookId.value)
        }
        return when (transport) {
            is AppResult.Failure -> AppResult.Failure(transport.error)
            is AppResult.Success -> {
                val response = transport.value
                if (response.isSuccessful) {
                    // A scan that answered with no word is reported as unknown rather than as a success
                    // with an invented conclusion (PRODUCT_SPEC 22.4).
                    AppResult.Success(response.body()?.result.orEmpty().ifEmpty { UNKNOWN_SCAN })
                } else {
                    AppResult.Failure(errors.fromStatus(response.code()))
                }
            }
        }
    }

    private suspend fun outcomeOf(
        profileId: ProfileId,
        bookId: LibraryItemId,
        result: String,
    ): AppResult<ItemScanOutcome> {
        logger.info(LogCategory.Sync, "Rescanned an item", LogField.Public("result", result))
        // `REMOVED` means the item is gone, which is the one conclusion with nothing left to refresh —
        // asking for it would produce a `404` and turn a successful scan into a reported failure.
        if (result == SCAN_REMOVED) return AppResult.Success(ItemScanOutcome(result, book = null))

        return when (val refreshed = library.fetchBook(profileId, bookId)) {
            is AppResult.Failure -> AppResult.Failure(refreshed.error)
            is AppResult.Success -> AppResult.Success(ItemScanOutcome(result, refreshed.value))
        }
    }

    override suspend fun removeFromDatabase(profileId: ProfileId, bookId: LibraryItemId): AppResult<Unit> {
        val connection = connections.connectionFor(profileId)
            ?: return AppResult.Failure(AppError.Authentication())

        val transport = resultOf(onError = errors::fromThrowable) {
            services.managementService(connection.serverUrl)
                .deleteItem(bearerOf(connection.accessToken.value), bookId.value)
        }
        return when (transport) {
            is AppResult.Failure -> AppResult.Failure(transport.error)
            is AppResult.Success -> {
                // `text/plain "OK"`, deliberately not read. The status is the whole answer.
                transport.value.body()?.close()
                if (transport.value.isSuccessful) {
                    logger.info(LogCategory.Sync, "Removed an item from the server database")
                    AppResult.Success(Unit)
                } else {
                    AppResult.Failure(errors.fromStatus(transport.value.code()))
                }
            }
        }
    }

    override suspend fun listUsers(profileId: ProfileId): AppResult<List<ServerUser>> {
        val connection = connections.connectionFor(profileId)
            ?: return AppResult.Failure(AppError.Authentication())

        val transport = resultOf(onError = errors::fromThrowable) {
            services.managementService(connection.serverUrl).listUsers(bearerOf(connection.accessToken.value))
        }
        return when (transport) {
            is AppResult.Failure -> AppResult.Failure(transport.error)
            is AppResult.Success -> {
                val response = transport.value
                if (response.isSuccessful) {
                    // An account with no id cannot be acted on, so it is dropped rather than shown as a row
                    // whose buttons would fail.
                    AppResult.Success(response.body()?.users.orEmpty().mapNotNull(::userOf))
                } else {
                    AppResult.Failure(errors.fromStatus(response.code()))
                }
            }
        }
    }

    override suspend fun createUser(profileId: ProfileId, user: NewServerUser): AppResult<ServerUser> {
        val connection = connections.connectionFor(profileId)
            ?: return AppResult.Failure(AppError.Authentication())

        val transport = resultOf(onError = errors::fromThrowable) {
            services.managementService(connection.serverUrl).createUser(
                bearer = bearerOf(connection.accessToken.value),
                request = CreateUserRequestDto(
                    username = user.username,
                    password = user.password,
                    type = user.accountType,
                    // Explicit, always. The server reads `!!req.body.isActive`, so an omitted flag creates
                    // an account that cannot sign in.
                    isActive = user.isActive,
                    permissions = CreateUserPermissionsDto(
                        download = user.canDownload,
                        update = user.canUpdate,
                        delete = user.canDelete,
                        upload = user.canUpload,
                    ),
                ),
            )
        }
        return when (transport) {
            is AppResult.Failure -> AppResult.Failure(transport.error)
            is AppResult.Success -> createdUserOf(transport.value)
        }
    }

    private fun createdUserOf(response: retrofit2.Response<CreateUserResponseDto>): AppResult<ServerUser> {
        if (!response.isSuccessful) return AppResult.Failure(errors.fromStatus(response.code()))
        val created = response.body()?.user?.let(::userOf)
            ?: return AppError.ApiCompatibility(
                summary = "The server created the account but did not describe it.",
                missingField = "user",
            ).asFailure()
        // The username is the administrator's own choice and is not private in the sense 14.5 means, but it
        // is still somebody's identity — the count is enough to say the operation happened.
        logger.info(LogCategory.Settings, "Created a server account")
        return AppResult.Success(created)
    }

    override suspend fun setUserActive(profileId: ProfileId, userId: String, isActive: Boolean): AppResult<Unit> {
        val connection = connections.connectionFor(profileId)
            ?: return AppResult.Failure(AppError.Authentication())

        val transport = resultOf(onError = errors::fromThrowable) {
            services.managementService(connection.serverUrl).updateUser(
                bearer = bearerOf(connection.accessToken.value),
                userId = userId,
                // One key. Sending the whole user back would rewrite permissions the administrator did not
                // open this screen to change.
                body = buildJsonObject { put("isActive", isActive) },
            )
        }
        return when (transport) {
            is AppResult.Failure -> AppResult.Failure(transport.error)
            is AppResult.Success -> {
                transport.value.body()?.close()
                if (transport.value.isSuccessful) {
                    AppResult.Success(Unit)
                } else {
                    AppResult.Failure(errors.fromStatus(transport.value.code()))
                }
            }
        }
    }

    /** A listed account. The token the server sent alongside it was never parsed — see [UserSummaryDto]. */
    private fun userOf(dto: UserSummaryDto): ServerUser? {
        val id = dto.id?.takeIf(String::isNotBlank) ?: return null
        val permissions = dto.permissions
        return ServerUser(
            id = id,
            username = dto.username.orEmpty(),
            accountType = dto.type.orEmpty(),
            isActive = dto.isActive,
            isLocked = dto.isLocked,
            canDownload = permissions?.download == true,
            canUpdate = permissions?.update == true,
            canDelete = permissions?.delete == true,
            canUpload = permissions?.upload == true,
            hasAllLibraryAccess = permissions?.accessAllLibraries == true,
            accessibleLibraryIds = dto.librariesAccessible,
        )
    }

    private fun candidateOf(provider: String, dto: MatchCandidateDto): MatchCandidate? {
        val title = dto.title?.takeIf(String::isNotBlank) ?: return null
        return MatchCandidate(
            provider = provider,
            title = title,
            subtitle = dto.subtitle,
            author = dto.author,
            narrator = dto.narrator,
            publisher = dto.publisher,
            publishedYear = dto.publishedYear,
            // Plain text or nothing. The HTML `description` is deliberately never read: MGR-003 wants
            // match results sanitized, and refusing to handle markup at all is a stronger guarantee than
            // stripping it — there is no tag list to get wrong and nothing to render by accident.
            description = dto.descriptionPlain,
            coverUrl = dto.cover,
            isbn = dto.isbn,
            asin = dto.asin,
            genres = dto.genres.orEmpty(),
            series = dto.series.orEmpty().mapNotNull { entry ->
                entry.series?.takeIf(String::isNotBlank)?.let { name -> SeriesEdit(name, entry.sequence.orEmpty()) }
            },
            language = dto.language,
        )
    }

    /**
     * PRODUCT_SPEC MGR-002 — the part the server will actually accept.
     *
     * Two things here are contract rather than convention, and both are easy to get wrong:
     *
     * - the field name is exactly `cover`;
     * - the **filename's extension** is what the server validates, so it is derived from the MIME type
     *   rather than from whatever the Photo Picker called the file. A `.jpeg` and a `.jpg` are both
     *   accepted; no extension at all is not.
     */
    private fun coverPartOf(image: CoverUpload): MultipartBody.Part = MultipartBody.Part.createFormData(
        COVER_FIELD,
        "cover.${EXTENSIONS[image.mimeType] ?: DEFAULT_EXTENSION}",
        image.bytes.toRequestBody(image.mimeType.toMediaTypeOrNull()),
    )

    private companion object {
        /** The multipart field name the server reads. Not configurable, not guessable. */
        const val COVER_FIELD = "cover"

        /** The scan conclusion that leaves nothing to re-read. */
        const val SCAN_REMOVED = "REMOVED"

        /** A scan that answered with no word at all. Reported rather than invented. */
        const val UNKNOWN_SCAN = "UNKNOWN"

        /**
         * The four extensions the server accepts, keyed by the MIME types Android reports.
         *
         * `image/jpg` is not a real MIME type and is here anyway: some content providers report it, and
         * refusing a JPEG because a device spelled its type unusually would be this app's bug rather than
         * the user's.
         */
        val EXTENSIONS = mapOf(
            "image/png" to "png",
            "image/jpeg" to "jpg",
            "image/jpg" to "jpg",
            "image/webp" to "webp",
        )

        /** Only reachable if validation let an unknown type through, which it does not. */
        const val DEFAULT_EXTENSION = "jpg"
    }
}
