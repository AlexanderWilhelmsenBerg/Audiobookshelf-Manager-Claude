package com.example.shelfplayer.domain.library

import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.core.model.SeriesSequence
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Series
import com.example.shelfplayer.core.model.library.SeriesMembership
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeriesDisplayLabelTest {
    private fun membership(name: String, sequence: String?, primary: Boolean = false) = SeriesMembership(
        series = Series(ServerId("server-1"), SeriesId(name), name),
        sequence = SeriesSequence.parse(sequence),
        isPrimary = primary,
    )

    @Test
    fun `the series number precedes its name without losing server precision`() {
        assertEquals("#12.5 Tidewatch", SeriesDisplayLabel.primary(listOf(membership("Tidewatch", "12.5"))))
        assertEquals("#03 Tidewatch", SeriesDisplayLabel.primary(listOf(membership("Tidewatch", "03"))))
    }

    @Test
    fun `an absent or blank sequence leaves only the series name`() {
        assertEquals("Tidewatch", SeriesDisplayLabel.primary(listOf(membership("Tidewatch", null))))
        assertEquals("Tidewatch", SeriesDisplayLabel.primary(listOf(membership("Tidewatch", "  "))))
        assertNull(SeriesDisplayLabel.primary(emptyList()))
    }

    @Test
    fun `primary membership wins without changing canonical series order`() {
        assertEquals(
            "#2 Primary",
            SeriesDisplayLabel.primary(
                listOf(membership("First", "9"), membership("Primary", "2", primary = true)),
            ),
        )
    }
}
