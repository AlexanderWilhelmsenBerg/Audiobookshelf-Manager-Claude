package com.example.shelfplayer.core.network.api

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.asFailure
import com.example.shelfplayer.core.model.library.BookMetadataEdit
import com.example.shelfplayer.core.model.library.BookMetadataField
import com.example.shelfplayer.core.model.library.BookSnapshot
import com.example.shelfplayer.core.model.resultOf
import com.example.shelfplayer.core.network.gateway.LibraryApi
import com.example.shelfplayer.core.network.gateway.ManagementApi
import com.example.shelfplayer.core.network.gateway.ProfileConnection
import com.example.shelfplayer.core.network.gateway.ProfileConnectionResolver
import com.example.shelfplayer.core.network.http.NetworkErrorMapper
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
}
