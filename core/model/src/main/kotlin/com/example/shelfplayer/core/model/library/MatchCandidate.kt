package com.example.shelfplayer.core.model.library

/**
 * PRODUCT_SPEC MGR-003 — one candidate a metadata provider offered, and nothing has been changed yet.
 *
 * ### Everything is optional
 *
 * The shape varies by provider. Google returns ten fields, Audible returns those plus a narrator, a
 * duration and a series; a custom provider returns whatever its author chose. A field that is required
 * here would turn one provider's omission into an unusable result.
 *
 * ### `description` is untrusted, and `coverUrl` is somebody else's host
 *
 * MGR-003 requires match results to be "treated as untrusted display data and sanitized": [description] is
 * provider-supplied HTML and must never be rendered as markup. MGR-002 requires that "tokens are not
 * appended to third-party cover URLs", and [coverUrl] is precisely such a URL — it points at Google or
 * Audible, not at the user's server, so the app's `Authorization` header must never travel with it.
 */
data class MatchCandidate(
    val provider: String,
    val title: String,
    val subtitle: String?,
    val author: String?,
    val narrator: String?,
    val publisher: String?,
    val publishedYear: String?,
    val description: String?,
    val coverUrl: String?,
    val isbn: String?,
    val asin: String?,
    val genres: List<String>,
    val series: List<SeriesEdit>,
    val language: String?,
) {
    /**
     * PRODUCT_SPEC MGR-003 — "existing non-empty fields are not overwritten without an explicit choice".
     *
     * Answers which fields this candidate *could* change, so the user can see them before choosing. A field
     * the candidate has no value for is never offered: a provider that does not know the publisher must not
     * be able to erase one the user already has.
     */
    fun changesAgainst(current: BookMetadataEdit): Set<BookMetadataField> = buildSet {
        offer(BookMetadataField.Title, title, current.title)
        offer(BookMetadataField.Subtitle, subtitle, current.subtitle)
        offer(BookMetadataField.Authors, author, current.authors.joinToString(", "))
        offer(BookMetadataField.Narrators, narrator, current.narrators.joinToString(", "))
        offer(BookMetadataField.Publisher, publisher, current.publisher)
        offer(BookMetadataField.PublishedYear, publishedYear, current.publishedYear)
        offer(BookMetadataField.Description, description, current.description)
        offer(BookMetadataField.Isbn, isbn, current.isbn)
        offer(BookMetadataField.Asin, asin, current.asin)
        offer(BookMetadataField.Language, language, current.language)
        if (genres.isNotEmpty() && genres != current.genres) add(BookMetadataField.Genres)
        if (series.isNotEmpty() && series != current.series) add(BookMetadataField.Series)
    }

    /**
     * PRODUCT_SPEC MGR-003 — applies only [fields], leaving everything else exactly as it was.
     *
     * The set is the user's explicit choice, which is what the requirement asks for. A field not in it is
     * not touched, even when the candidate has a value for it.
     */
    fun applyTo(current: BookMetadataEdit, fields: Set<BookMetadataField>): BookMetadataEdit = current.copy(
        title = if (BookMetadataField.Title in fields) title else current.title,
        subtitle = pick(BookMetadataField.Subtitle in fields, subtitle, current.subtitle),
        authors = if (BookMetadataField.Authors in fields) splitNames(author) else current.authors,
        narrators = if (BookMetadataField.Narrators in fields) splitNames(narrator) else current.narrators,
        publisher = pick(BookMetadataField.Publisher in fields, publisher, current.publisher),
        publishedYear = pick(BookMetadataField.PublishedYear in fields, publishedYear, current.publishedYear),
        description = pick(BookMetadataField.Description in fields, description, current.description),
        isbn = pick(BookMetadataField.Isbn in fields, isbn, current.isbn),
        asin = pick(BookMetadataField.Asin in fields, asin, current.asin),
        language = pick(BookMetadataField.Language in fields, language, current.language),
        genres = if (BookMetadataField.Genres in fields) genres else current.genres,
        series = if (BookMetadataField.Series in fields) series else current.series,
    )

    private fun MutableSet<BookMetadataField>.offer(field: BookMetadataField, offered: String?, current: String) {
        if (!offered.isNullOrBlank() && offered.trim() != current.trim()) add(field)
    }

    private fun pick(chosen: Boolean, offered: String?, current: String): String =
        if (chosen && !offered.isNullOrBlank()) offered.trim() else current

    /**
     * Providers join multiple people with a comma, which is the same shape the editor's list fields use.
     *
     * Its known failure is the same one: `Smith, Jr.` becomes two people. Recorded in `docs/gaps.md` rather
     * than worked around here, because working around it would mean guessing which commas are separators.
     */
    private fun splitNames(joined: String?): List<String> =
        joined?.split(",")?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
}
