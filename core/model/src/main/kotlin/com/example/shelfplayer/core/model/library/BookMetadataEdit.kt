package com.example.shelfplayer.core.model.library

/**
 * PRODUCT_SPEC MGR-001 — one editable field, named so that dirtiness and validation can be talked about.
 *
 * The set exists rather than a `Map<String, …>` because the two things that read it — the dirty set and the
 * validation errors — must agree on what a field *is*, and a string key lets them disagree silently.
 */
enum class BookMetadataField {
    Title,
    Subtitle,
    Authors,
    Narrators,
    Series,
    Genres,
    Tags,
    PublishedYear,
    Publisher,
    Description,
    Isbn,
    Asin,
    Language,
    Explicit,
    Abridged,
}

/** PRODUCT_SPEC MGR-001 — a series membership as the editor holds it: a name and a free-text sequence. */
data class SeriesEdit(val name: String, val sequence: String) {
    val isBlank: Boolean get() = name.isBlank()
}

/**
 * PRODUCT_SPEC MGR-001 — the editable half of a book, as text.
 *
 * ### Why every scalar is a non-null `String`
 *
 * A text field holds `""`, not `null`. Modelling these as `String?` would put the null-versus-empty question
 * in front of every reader of the form, and each of them would answer it slightly differently — which is how
 * a field that the user cleared ends up being sent as `""` on one screen and omitted on another.
 *
 * The translation happens once, at the wire edge, and it matches what the server does with the value anyway:
 * an empty string is stored as `null`.
 *
 * ### Why `publishedYear` is a `String` when [Book] has an `Int?`
 *
 * Because the user types characters, and a half-typed year is a state the editor has to be able to hold.
 * Parsing on every keystroke would delete the `2` the moment somebody starts typing `2024`. It is validated
 * on save instead, which is also where the server would reject it.
 */
data class BookMetadataEdit(
    val title: String,
    val subtitle: String,
    val authors: List<String>,
    val narrators: List<String>,
    val series: List<SeriesEdit>,
    val genres: List<String>,
    val tags: List<String>,
    val publishedYear: String,
    val publisher: String,
    val description: String,
    val isbn: String,
    val asin: String,
    val language: String,
    val isExplicit: Boolean,
    val isAbridged: Boolean,
) {
    /**
     * PRODUCT_SPEC MGR-001 — "dirty fields are tracked".
     *
     * The comparison is against the trimmed, blank-dropped form rather than the raw one, so that adding and
     * then deleting a genre leaves the field clean. Whitespace the user did not mean to add is not a change,
     * and reporting it as one produces a save that changes nothing and an "unsaved draft" that never clears.
     */
    fun changesFrom(original: BookMetadataEdit): Set<BookMetadataField> {
        val mine = normalized()
        val theirs = original.normalized()
        return VALUES
            .filterKeys { field -> VALUES.getValue(field)(mine) != VALUES.getValue(field)(theirs) }
            .keys
    }

    /**
     * PRODUCT_SPEC MGR-001 — "validation errors are inline".
     *
     * Deliberately short. Audiobookshelf validates almost nothing on this route — it accepts any string for
     * `isbn`, `asin` and `language` — so a client that rejected a malformed ISBN would be enforcing a rule
     * the server does not have, on a self-hosted library whose owner may have good reasons for the value
     * they typed (PRODUCT_SPEC 22.4: do not guess undocumented server behaviour).
     *
     * What is checked is what this *app* cannot represent or what would damage the item:
     *
     * - A blank title is stored as `null` by the server, which leaves an item with no name in every list.
     * - A published year that is not a number is dropped by [Book], so the app would show a value the user
     *   cannot see and cannot correct.
     * - A series entry with a sequence and no name cannot be sent: the server drops the whole array if any
     *   entry lacks a name, so one malformed row silently discards every other series on the book.
     */
    fun validate(): Map<BookMetadataField, BookMetadataError> = buildMap {
        if (title.isBlank()) put(BookMetadataField.Title, BookMetadataError.TitleRequired)
        val year = publishedYear.trim()
        if (year.isNotEmpty() && year.toIntOrNull() == null) {
            put(BookMetadataField.PublishedYear, BookMetadataError.YearNotANumber)
        }
        if (series.any { it.isBlank && it.sequence.isNotBlank() }) {
            put(BookMetadataField.Series, BookMetadataError.SeriesNameRequired)
        }
    }

    /**
     * The form as it would be sent: trimmed, with blank list entries dropped.
     *
     * Used for comparison and for the wire, so that what "clean" means and what is transmitted cannot
     * disagree — a difference between those two is a save button that stays enabled after a successful save.
     */
    fun normalized(): BookMetadataEdit = copy(
        title = title.trim(),
        subtitle = subtitle.trim(),
        authors = authors.map(String::trim).filter(String::isNotEmpty),
        narrators = narrators.map(String::trim).filter(String::isNotEmpty),
        series = series.map { SeriesEdit(it.name.trim(), it.sequence.trim()) }.filterNot(SeriesEdit::isBlank),
        genres = genres.map(String::trim).filter(String::isNotEmpty),
        tags = tags.map(String::trim).filter(String::isNotEmpty),
        publishedYear = publishedYear.trim(),
        publisher = publisher.trim(),
        description = description.trim(),
        isbn = isbn.trim(),
        asin = asin.trim(),
        language = language.trim(),
    )

    companion object {
        /**
         * Every field, and how to read it.
         *
         * A table rather than fifteen `if`s, because fifteen near-identical comparisons is a place to
         * forget one — and a forgotten field is an edit the user makes, watches the save button stay
         * greyed out for, and loses.
         *
         * `BookMetadataEditTest` asserts the table covers every [BookMetadataField], which is the
         * exhaustiveness a `when` would have given.
         */
        internal val VALUES: Map<BookMetadataField, (BookMetadataEdit) -> Any?> = mapOf(
            BookMetadataField.Title to { it.title },
            BookMetadataField.Subtitle to { it.subtitle },
            BookMetadataField.Authors to { it.authors },
            BookMetadataField.Narrators to { it.narrators },
            BookMetadataField.Series to { it.series },
            BookMetadataField.Genres to { it.genres },
            BookMetadataField.Tags to { it.tags },
            BookMetadataField.PublishedYear to { it.publishedYear },
            BookMetadataField.Publisher to { it.publisher },
            BookMetadataField.Description to { it.description },
            BookMetadataField.Isbn to { it.isbn },
            BookMetadataField.Asin to { it.asin },
            BookMetadataField.Language to { it.language },
            BookMetadataField.Explicit to { it.isExplicit },
            BookMetadataField.Abridged to { it.isAbridged },
        )

        /** The form as the cached book fills it. */
        fun of(book: Book): BookMetadataEdit = BookMetadataEdit(
            title = book.title,
            subtitle = book.subtitle.orEmpty(),
            authors = book.authors.map { it.name },
            narrators = book.narrators,
            // `SeriesSequence.raw` is the server's own text, which is what the editor must round-trip: a
            // book at `Book 4` must not come back as `4` because this app parsed it (PRODUCT_SPEC LIB-003).
            series = book.seriesMemberships.map { SeriesEdit(it.series.name, it.sequence.raw) },
            genres = book.genres,
            tags = book.tags,
            publishedYear = book.publishedYear?.toString().orEmpty(),
            publisher = book.publisher.orEmpty(),
            description = book.description.orEmpty(),
            isbn = book.isbn.orEmpty(),
            asin = book.asin.orEmpty(),
            language = book.language.orEmpty(),
            isExplicit = book.isExplicit,
            isAbridged = book.isAbridged,
        )
    }
}

/** PRODUCT_SPEC MGR-001 — why one field cannot be saved. The message is the caller's to phrase. */
enum class BookMetadataError {
    TitleRequired,
    YearNotANumber,
    SeriesNameRequired,
}
