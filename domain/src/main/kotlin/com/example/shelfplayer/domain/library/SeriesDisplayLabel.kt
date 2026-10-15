package com.example.shelfplayer.domain.library

import com.example.shelfplayer.core.model.library.SeriesMembership

/**
 * #101 / #265 — a display-only label for the selected primary (or first) series.
 *
 * Car metadata prioritizes the sequence before the name so that the identifying volume
 * stays visible when a host truncates secondary text. This must never be used as a
 * series identity, sequence sorter, or a replacement for the server's original text.
 */
object SeriesDisplayLabel {
    fun primary(memberships: List<SeriesMembership>): String? {
        val membership = memberships.firstOrNull(SeriesMembership::isPrimary)
            ?: memberships.firstOrNull()
            ?: return null
        val name = membership.series.name.trim().takeIf(String::isNotEmpty) ?: return null
        val sequence = membership.sequence.raw.trim().takeIf(String::isNotEmpty)
        return if (sequence == null) name else "#$sequence $name"
    }
}
