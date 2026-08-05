package com.example.shelfplayer.core.model

/**
 * PRODUCT_SPEC LIB-003 — normalized position of a book inside a series.
 *
 * Servers expose the sequence as free text: `1`, `2.5`, `03`, `Book 4`, `Prequel`. Sorting that text
 * lexicographically puts `10` before `2`, which is the classic audiobook-series bug. This type
 * parses the leading decimal when one exists and otherwise keeps the raw text.
 *
 * Ordering contract:
 *  - numeric sequences sort numerically;
 *  - non-numeric sequences sort *after* every numeric one;
 *  - non-numeric sequences are ordered case-insensitively by their raw text, which makes the sort
 *    stable and reproducible rather than dependent on server order.
 */
sealed interface SeriesSequence : Comparable<SeriesSequence> {
    /** The text exactly as the server provided it, for display. */
    val raw: String

    data class Numeric(override val raw: String, val value: Double) : SeriesSequence

    data class Unparsed(override val raw: String) : SeriesSequence

    /** No sequence was provided at all. Sorts last, after [Unparsed]. */
    data object Absent : SeriesSequence {
        override val raw: String = ""
    }

    override fun compareTo(other: SeriesSequence): Int {
        val rank = rankOf(this).compareTo(rankOf(other))
        if (rank != 0) return rank
        return when {
            this is Numeric && other is Numeric -> value.compareTo(other.value)
            this is Unparsed && other is Unparsed ->
                raw.compareTo(other.raw, ignoreCase = true)
            else -> 0
        }
    }

    companion object {
        private const val RANK_NUMERIC = 0
        private const val RANK_UNPARSED = 1
        private const val RANK_ABSENT = 2

        private fun rankOf(sequence: SeriesSequence): Int = when (sequence) {
            is Numeric -> RANK_NUMERIC
            is Unparsed -> RANK_UNPARSED
            Absent -> RANK_ABSENT
        }

        /**
         * Leading decimal number, optionally signed, e.g. `2`, `2.5`, `03`, `2.5 (omnibus)`.
         *
         * Anything the server appends after the number is preserved in [raw] but does not take part
         * in ordering: `2.5` and `2.5 (omnibus)` are the same position in the series.
         */
        private val LEADING_DECIMAL = Regex("""^\s*([+-]?\d+(?:[.,]\d+)?)""")

        fun parse(raw: String?): SeriesSequence {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return Absent
            val numeric = LEADING_DECIMAL.find(text)
                ?.groupValues
                ?.get(1)
                ?.replace(',', '.')
                ?.toDoubleOrNull()
            return if (numeric == null) Unparsed(text) else Numeric(text, numeric)
        }
    }
}
