package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.common.connectivity.NetworkMonitor
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ManagementAction
import com.example.shelfplayer.core.model.ManagementBlock
import com.example.shelfplayer.core.model.ManagementPermissions
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.BookMetadataEdit
import com.example.shelfplayer.core.model.library.BookMetadataField
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.MetadataRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.first
import java.util.Locale
import javax.inject.Inject

/**
 * PRODUCT_SPEC MGR-001 / MGR-008 — replace one genre on every matching book.
 *
 * This composes the already captured, permission-gated item metadata contract. There is no guessed bulk
 * endpoint: each book is reloaded and updated through [MetadataRepository], in order, with only
 * [BookMetadataField.Genres] marked dirty. That keeps unrelated metadata out of the request and lets the
 * repository write every accepted server response through the ordinary Room catalogue path.
 *
 * ### Why this is sequential
 *
 * The metadata PATCH has no idempotency key, no ETag and no bulk transaction. Parallel writes would turn a
 * single click into an unbounded burst and make a permission revocation race every in-flight request. One at
 * a time is deliberately slower and gives a systemic failure or profile switch a clean boundary at which
 * to stop. A pre-existing per-book draft is a conflict and is never sent through the save path that would
 * discard it.
 */
class BulkEditGenresUseCase @Inject constructor(
    private val profiles: ProfileRepository,
    private val libraries: LibraryRepository,
    private val metadata: MetadataRepository,
    private val auth: AuthRepository,
    private val network: NetworkMonitor,
) {
    /**
     * @param profileId explicit because this is a privileged write (PRODUCT_SPEC 5.2).
     * @param sourceGenre the genre selected from the cached genre group.
     * @param targetGenres one or more comma-separated replacement genres.
     */
    suspend operator fun invoke(
        profileId: ProfileId,
        sourceGenre: String,
        targetGenres: String,
    ): AppResult<BulkGenreEditSummary> {
        val replacement = when (val parsed = GenreReplacement.parse(sourceGenre, targetGenres)) {
            is AppResult.Failure -> return parsed
            is AppResult.Success -> parsed.value
        }

        when (val authorization = authorize(profileId)) {
            is AppResult.Failure -> return authorization
            is AppResult.Success -> Unit
        }

        val matching = libraries.observeAccessibleBooks(profileId).first()
            .filter { book -> replacement.matchesAny(book.genres) }
        return AppResult.Success(editMatches(profileId, matching, replacement))
    }

    private suspend fun authorize(profileId: ProfileId): AppResult<Profile> {
        val profile = profiles.observeActiveProfile().first()
            ?: return AppResult.Failure(
                AppError.Authentication(
                    summary = "Sign in to a server before editing genres.",
                    requiresReauthentication = false,
                ),
            )
        return when {
            profile.id != profileId -> profileChangedFailure()
            profile.requiresReauthentication ->
                AppResult.Failure(AppError.Authentication(summary = "Sign in again before editing genres."))
            else -> authorizeUpdate(profile)
        }
    }

    private suspend fun authorizeUpdate(profile: Profile): AppResult<Profile> {
        val permissions = ManagementPermissions(
            profileRole = profile.role,
            canUpdate = profile.canUpdate,
            canDelete = profile.canDelete,
            canUpload = profile.canUpload,
            // EditMetadata is an account-grant operation, not a server capability. Supplying the honest
            // unknown value keeps this policy on the shared ManagementPermissions implementation.
            capabilities = null,
            isOnline = network.isOnline.first(),
        )
        return permissions.blockOn(ManagementAction.EditMetadata)?.let { block ->
            AppResult.Failure(block.asGenreEditError())
        } ?: AppResult.Success(profile)
    }

    private suspend fun editMatches(
        profileId: ProfileId,
        matching: List<Book>,
        replacement: GenreReplacement,
    ): BulkGenreEditSummary {
        var updated = 0
        var unchanged = 0
        var locallyStale = 0
        val failures = mutableListOf<BulkGenreEditFailure>()
        var stopReason: AppError? = null

        var index = 0
        while (index < matching.size && stopReason == null) {
            when (val outcome = editOne(profileId, matching[index].id, replacement)) {
                is BookGenreEditOutcome.Updated -> {
                    updated += 1
                    if (outcome.isLocalCopyStale) locallyStale += 1
                }
                BookGenreEditOutcome.Unchanged -> unchanged += 1
                is BookGenreEditOutcome.Failed -> {
                    failures += outcome.failure
                    if (outcome.shouldStop) stopReason = outcome.failure.error
                }
                is BookGenreEditOutcome.Stopped -> stopReason = outcome.reason
            }
            index += 1
        }

        return BulkGenreEditSummary(
            matchedCount = matching.size,
            updatedCount = updated,
            unchangedCount = unchanged,
            locallyStaleCount = locallyStale,
            failures = failures,
            stopReason = stopReason,
        )
    }

    private suspend fun editOne(
        profileId: ProfileId,
        bookId: LibraryItemId,
        replacement: GenreReplacement,
    ): BookGenreEditOutcome {
        if (profiles.activeProfileId() != profileId) {
            return BookGenreEditOutcome.Stopped(profileChangedError())
        }
        existingDraftConflict(profileId, bookId)?.let { return it }

        return when (val reloaded = metadata.reload(profileId, bookId)) {
            is AppResult.Success -> editReloaded(profileId, reloaded.value, replacement)
            is AppResult.Failure -> failed(profileId, bookId, BulkGenreEditStage.Reload, reloaded.error)
        }
    }

    /** Re-check both account and draft state across the reload suspension before constructing a PATCH. */
    private suspend fun editReloaded(
        profileId: ProfileId,
        latest: Book,
        replacement: GenreReplacement,
    ): BookGenreEditOutcome {
        if (profiles.activeProfileId() != profileId) {
            return BookGenreEditOutcome.Stopped(profileChangedError())
        }
        existingDraftConflict(profileId, latest.id)?.let { return it }
        return editLatest(profileId, latest, replacement)
    }

    private suspend fun editLatest(
        profileId: ProfileId,
        latest: Book,
        replacement: GenreReplacement,
    ): BookGenreEditOutcome {
        // The cached group is selection state, never write authority. A book may have changed on the
        // server while the dialog was open; in that case it is no longer part of this operation.
        if (!replacement.matchesAny(latest.genres)) return BookGenreEditOutcome.Unchanged

        val genres = replacement.applyTo(latest.genres)
        if (genres == latest.genres) return BookGenreEditOutcome.Unchanged

        val edit = BookMetadataEdit.of(latest).copy(genres = genres)
        return save(profileId, latest.id, edit)
    }

    private suspend fun save(
        profileId: ProfileId,
        bookId: LibraryItemId,
        edit: BookMetadataEdit,
    ): BookGenreEditOutcome {
        // Reload is a network suspension point. Re-check afterwards so a profile switch that happened
        // while it was in flight cannot turn the refreshed item into a write from the previous account.
        if (profiles.activeProfileId() != profileId) {
            return BookGenreEditOutcome.Stopped(profileChangedError())
        }
        return when (
            val saved = metadata.save(
                profileId = profileId,
                bookId = bookId,
                edit = edit,
                changed = setOf(BookMetadataField.Genres),
            )
        ) {
            is AppResult.Success -> BookGenreEditOutcome.Updated(saved.value.isLocalCopyStale)
            is AppResult.Failure -> {
                // MGR-001: a failed write remains an explicit draft, never an unattended retry queue.
                // Check both boundaries once more after the network suspension: a profile switch must
                // not write recovery state into the old account, and another editor's draft must never
                // be overwritten by this operation's recovery copy.
                if (
                    profiles.activeProfileId() == profileId &&
                    metadata.observeDraft(profileId, bookId).first() == null
                ) {
                    metadata.saveDraft(profileId, bookId, edit)
                }
                failed(profileId, bookId, BulkGenreEditStage.Save, saved.error)
            }
        }
    }

    private suspend fun existingDraftConflict(
        profileId: ProfileId,
        bookId: LibraryItemId,
    ): BookGenreEditOutcome.Failed? = metadata.observeDraft(profileId, bookId).first()?.let {
        BookGenreEditOutcome.Failed(
            failure = BulkGenreEditFailure(
                bookId = bookId,
                stage = BulkGenreEditStage.Draft,
                error = AppError.Conflict(summary = "This book already has an unsaved metadata draft."),
            ),
            shouldStop = false,
        )
    }

    private suspend fun failed(
        profileId: ProfileId,
        bookId: LibraryItemId,
        stage: BulkGenreEditStage,
        error: AppError,
    ) = BookGenreEditOutcome.Failed(
        failure = BulkGenreEditFailure(bookId, stage, error),
        shouldStop = shouldStop(profileId, error),
    )

    /** A systemic failure stops the burst; a `403` also updates the next permission decision. */
    private suspend fun shouldStop(profileId: ProfileId, error: AppError): Boolean = when (error) {
        is AppError.Authorization -> {
            auth.refreshPermissions(profileId)
            true
        }
        is AppError.Authentication -> true
        // One lost connection must not produce one request and one recovery draft for every cached book.
        // Server errors include 429, so this also respects Retry-After without special-casing one status.
        is AppError.Network, is AppError.Timeout, is AppError.Server -> true
        is AppError.ApiCompatibility,
        is AppError.Canceled,
        is AppError.Conflict,
        is AppError.Download,
        is AppError.Playback,
        is AppError.Security,
        is AppError.Storage,
        is AppError.Unknown,
        is AppError.Validation,
        -> false
    }

    private fun profileChangedFailure(): AppResult.Failure = AppResult.Failure(profileChangedError())

    private fun profileChangedError() =
        AppError.Canceled(summary = "The genre edit stopped because the active profile changed.")
}

private sealed interface BookGenreEditOutcome {
    data class Updated(val isLocalCopyStale: Boolean) : BookGenreEditOutcome

    data object Unchanged : BookGenreEditOutcome

    data class Failed(val failure: BulkGenreEditFailure, val shouldStop: Boolean) : BookGenreEditOutcome

    data class Stopped(val reason: AppError) : BookGenreEditOutcome
}

/**
 * The full outcome after a bulk genre edit started.
 *
 * An [AppResult.Success] does not imply every book saved. Once the first item has landed the operation can
 * no longer honestly collapse into one failure, so per-item failures and any early stop are reported here.
 */
data class BulkGenreEditSummary(
    val matchedCount: Int,
    val updatedCount: Int = 0,
    val unchangedCount: Int = 0,
    /** Successful server writes whose follow-up refresh failed; the repository kept the prior Room row. */
    val locallyStaleCount: Int = 0,
    val failures: List<BulkGenreEditFailure> = emptyList(),
    /** Non-null when a systemic network/server/auth failure or a profile switch stopped the loop. */
    val stopReason: AppError? = null,
) {
    val failedCount: Int get() = failures.size

    /** Books deliberately skipped because their individual metadata editor already owns unsaved work. */
    val draftConflictCount: Int get() = failures.count { it.stage == BulkGenreEditStage.Draft }

    val unprocessedCount: Int
        get() = (matchedCount - updatedCount - unchangedCount - failedCount).coerceAtLeast(0)
}

/** The item is intentionally identified without its private title (PRODUCT_SPEC 14.5). */
data class BulkGenreEditFailure(val bookId: LibraryItemId, val stage: BulkGenreEditStage, val error: AppError)

enum class BulkGenreEditStage {
    Draft,
    Reload,
    Save,
}

/** A validated replacement and its case-insensitive, stable-order transformation. */
private data class GenreReplacement(val source: String, val targets: List<String>) {
    private val targetsByKey = targets.associateBy(::keyOf)

    fun matchesAny(genres: List<String>): Boolean = genres.any { genre -> genre.trim().equals(source, true) }

    fun applyTo(genres: List<String>): List<String> {
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var inserted = false

        fun add(value: String) {
            val cleaned = value.trim()
            if (cleaned.isEmpty()) return
            val key = keyOf(cleaned)
            val canonicalTarget = targetsByKey[key] ?: cleaned
            if (seen.add(key)) result += canonicalTarget
        }

        genres.forEach { genre ->
            if (genre.trim().equals(source, ignoreCase = true)) {
                if (!inserted) {
                    targets.forEach(::add)
                    inserted = true
                }
            } else {
                add(genre)
            }
        }
        return result
    }

    companion object {
        fun parse(sourceGenre: String, targetGenres: String): AppResult<GenreReplacement> {
            val source = sourceGenre.trim()
            if (source.isEmpty()) {
                return AppResult.Failure(
                    AppError.Validation(
                        summary = "Choose the genre to replace.",
                        fieldErrors = mapOf("sourceGenre" to "A source genre is required."),
                    ),
                )
            }

            val seen = mutableSetOf<String>()
            val targets = targetGenres.split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .filter { target -> seen.add(keyOf(target)) }
            if (targets.isEmpty()) {
                return AppResult.Failure(
                    AppError.Validation(
                        summary = "Enter at least one replacement genre.",
                        fieldErrors = mapOf("targetGenres" to "A replacement genre is required."),
                    ),
                )
            }
            return AppResult.Success(GenreReplacement(source, targets))
        }
    }
}

private fun keyOf(value: String): String = value.lowercase(Locale.ROOT)

private fun ManagementBlock.asGenreEditError(): AppError = when (this) {
    ManagementBlock.Permission ->
        AppError.Authorization(summary = "This account is not allowed to update book metadata.")
    ManagementBlock.Offline ->
        AppError.Network(summary = "Connect to the server before editing genres.")
    ManagementBlock.Capability ->
        AppError.ApiCompatibility(summary = "This server has not confirmed metadata editing support.")
}
