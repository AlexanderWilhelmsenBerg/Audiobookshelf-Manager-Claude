package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.library.BookMetadataEdit
import com.example.shelfplayer.core.model.library.SeriesEdit
import com.example.shelfplayer.core.model.resultOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC MGR-001 — an unsaved edit, as one stored string.
 *
 * ### Why the stored shape is its own type
 *
 * [BookMetadataEdit] is a domain model, and serializing it directly would make its field names a storage
 * format: renaming one would silently orphan every draft on every device. [StoredDraft] is the format, and
 * it changes only when somebody means to change it.
 *
 * ### An unreadable draft is discarded, not thrown
 *
 * A draft written by a newer build — one with a field this one has never heard of, or missing one it needs
 * — must not stop the editor from opening. [decode] answers `null`, the caller logs it, and the user gets
 * the book's current metadata instead of a crash. That is a deliberate loss of *their* text, so it is the
 * one thing here worth a log line; it is not a swallowed exception, because nothing was in flight and the
 * outcome is reported to the caller (ADR-0003).
 */
@Singleton
class MetadataDraftCodec @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(edit: BookMetadataEdit): String = json.encodeToString(
        StoredDraft.serializer(),
        StoredDraft(
            title = edit.title,
            subtitle = edit.subtitle,
            authors = edit.authors,
            narrators = edit.narrators,
            series = edit.series.map { StoredSeries(it.name, it.sequence) },
            genres = edit.genres,
            tags = edit.tags,
            publishedYear = edit.publishedYear,
            publisher = edit.publisher,
            description = edit.description,
            isbn = edit.isbn,
            asin = edit.asin,
            language = edit.language,
            isExplicit = edit.isExplicit,
            isAbridged = edit.isAbridged,
        ),
    )

    /**
     * A failure when the stored text is not a draft this build can read.
     *
     * Through `resultOf`, the project's single exception boundary (ADR-0003): the throwable is carried in
     * the result rather than dropped, cancellation is rethrown, and the caller decides what to say. That
     * is what makes discarding an unreadable draft a reported outcome instead of a swallowed exception.
     */
    fun decode(payload: String): AppResult<BookMetadataEdit> = resultOf(
        onError = { cause -> AppError.Unknown(summary = "That saved draft could not be read.", cause = cause) },
    ) {
        val stored = json.decodeFromString(StoredDraft.serializer(), payload)
        BookMetadataEdit(
            title = stored.title,
            subtitle = stored.subtitle,
            authors = stored.authors,
            narrators = stored.narrators,
            series = stored.series.map { SeriesEdit(it.name, it.sequence) },
            genres = stored.genres,
            tags = stored.tags,
            publishedYear = stored.publishedYear,
            publisher = stored.publisher,
            description = stored.description,
            isbn = stored.isbn,
            asin = stored.asin,
            language = stored.language,
            isExplicit = stored.isExplicit,
            isAbridged = stored.isAbridged,
        )
    }

    /** The stored format. Every field defaulted, so a draft from an older build still opens. */
    @Serializable
    private data class StoredDraft(
        val title: String = "",
        val subtitle: String = "",
        val authors: List<String> = emptyList(),
        val narrators: List<String> = emptyList(),
        val series: List<StoredSeries> = emptyList(),
        val genres: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val publishedYear: String = "",
        val publisher: String = "",
        val description: String = "",
        val isbn: String = "",
        val asin: String = "",
        val language: String = "",
        val isExplicit: Boolean = false,
        val isAbridged: Boolean = false,
    )

    @Serializable
    private data class StoredSeries(val name: String = "", val sequence: String = "")
}
