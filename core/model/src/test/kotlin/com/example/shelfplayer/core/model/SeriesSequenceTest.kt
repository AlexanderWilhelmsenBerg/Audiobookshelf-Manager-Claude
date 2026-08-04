package com.example.shelfplayer.core.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PRODUCT_SPEC LIB-003 — series sequence parsing and ordering. */
class SeriesSequenceTest {

    @Test
    fun `parses plain and decimal numbers`() {
        assertEquals(SeriesSequence.Numeric("1", 1.0), SeriesSequence.parse("1"))
        assertEquals(SeriesSequence.Numeric("2.5", 2.5), SeriesSequence.parse("2.5"))
        assertEquals(SeriesSequence.Numeric("03", 3.0), SeriesSequence.parse("03"))
    }

    @Test
    fun `accepts a comma decimal separator`() {
        assertEquals(SeriesSequence.Numeric("2,5", 2.5), SeriesSequence.parse("2,5"))
    }

    @Test
    fun `keeps trailing text but orders on the leading number`() {
        val parsed = SeriesSequence.parse("2.5 (omnibus)")
        assertEquals(SeriesSequence.Numeric("2.5 (omnibus)", 2.5), parsed)
        assertEquals(0, parsed.compareTo(SeriesSequence.parse("2.5")))
    }

    @Test
    fun `treats blank and null as absent`() {
        assertEquals(SeriesSequence.Absent, SeriesSequence.parse(null))
        assertEquals(SeriesSequence.Absent, SeriesSequence.parse("   "))
    }

    @Test
    fun `keeps non-numeric sequences as raw text`() {
        assertEquals(SeriesSequence.Unparsed("Prequel"), SeriesSequence.parse("Prequel"))
    }

    /** The classic bug this type exists to prevent: `10` sorting before `2`. */
    @Test
    fun `sorts numerically rather than lexicographically`() {
        val sorted = listOf("10", "2", "1", "2.5")
            .map(SeriesSequence::parse)
            .sorted()
            .map(SeriesSequence::raw)

        assertEquals(listOf("1", "2", "2.5", "10"), sorted)
    }

    @Test
    fun `sorts non-numeric sequences after every numeric one, and absent last`() {
        val sorted = listOf("Prequel", "10", null, "1", "Bonus")
            .map(SeriesSequence::parse)
            .sorted()
            .map(SeriesSequence::raw)

        assertEquals(listOf("1", "10", "Bonus", "Prequel", ""), sorted)
    }

    @Test
    fun `ordering of non-numeric sequences is stable and case-insensitive`() {
        assertTrue(SeriesSequence.parse("apple") < SeriesSequence.parse("Banana"))
        assertTrue(SeriesSequence.parse("Banana") > SeriesSequence.parse("APPLE"))
    }
}
