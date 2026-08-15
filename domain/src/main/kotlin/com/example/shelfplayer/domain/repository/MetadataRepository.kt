package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.BookMetadataEdit
import com.example.shelfplayer.core.model.library.BookMetadataField
import kotlinx.coroutines.flow.Flow

/**
 * PRODUCT_SPEC MGR-001 — editing a book's metadata, and the draft that survives a failure to save it.
 *
 * ### Why drafts are a repository concern
 *
 * MGR-001 requires that "network failure retains an explicit unsaved draft locally" and that the user may
 * discard it. A draft held in a `ViewModel` satisfies neither: it dies with the process, and Android kills
 * the process behind a user who leaves the app to look up an ISBN — which is exactly the moment the draft
 * matters most.
 *
 * The same requirement also rules out the other obvious design. MGR-001 says privileged edits are **not**
 * queued for blind offline execution, so a draft is deliberately *not* an outbox: nothing retries it, and
 * it is applied only when the user asks again, in front of the conflict check.
 */
interface MetadataRepository {

    /** The saved draft for this book, or `null` when there is none. */
    fun observeDraft(profileId: ProfileId, bookId: LibraryItemId): Flow<BookMetadataEdit?>

    /**
     * Records the user's in-progress edit.
     *
     * Called when a save fails and when the editor is left, not on every keystroke: a draft written per
     * character is a write amplification problem, and the state that has to survive is the state at the
     * moment the user stopped, which both of those catch.
     */
    suspend fun saveDraft(profileId: ProfileId, bookId: LibraryItemId, edit: BookMetadataEdit)

    /** PRODUCT_SPEC MGR-001 — "user may discard a draft". */
    suspend fun discardDraft(profileId: ProfileId, bookId: LibraryItemId)

    /**
     * PRODUCT_SPEC MGR-001 — "editor loads the latest item before save".
     *
     * Re-reads the item from the server and writes it to Room, so the editor can compare what it started
     * from against what the server holds *now*. Returns the fresh book.
     *
     * This is the conflict check, and it is the only one available: Audiobookshelf's metadata route
     * carries no `ETag` and honours no `If-Match`, so there is no way to ask the server to refuse a stale
     * write. Detecting the conflict before sending is therefore not a nicety — it is the whole mechanism
     * (PRODUCT_SPEC 22.4: the app does not invent a concurrency control the server does not have).
     */
    suspend fun reload(profileId: ProfileId, bookId: LibraryItemId): AppResult<Book>

    /**
     * PRODUCT_SPEC MGR-001 — sends [changed] and nothing else, then refreshes the item.
     *
     * On success the draft is discarded and Room holds the server's own version, including whatever the
     * server's HTML sanitiser did to the description.
     *
     * @param changed **not a hint.** `authors` and `series` are replacements on this endpoint, so a
     *   caller that widened this set would delete entries the form did not know about.
     */
    suspend fun save(
        profileId: ProfileId,
        bookId: LibraryItemId,
        edit: BookMetadataEdit,
        changed: Set<BookMetadataField>,
    ): AppResult<Book>
}
