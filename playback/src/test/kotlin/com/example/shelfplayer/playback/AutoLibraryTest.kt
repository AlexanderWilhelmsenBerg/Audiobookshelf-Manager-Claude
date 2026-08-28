package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.LibraryItemId
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

/**
 * PRODUCT_SPEC 11.1 — the media ids the Android Auto tree hands out, and reads back.
 *
 * A car returns nothing but the id of what was tapped, so the id *is* the protocol. Everything about it can
 * be wrong in a way that only shows up in a car: a book whose id contains a slash, a position that does not
 * parse, a browsable node treated as playable. None of that needs a head unit to test.
 *
 * Robolectric only because `MediaItem` is an Android type. Nothing here constructs an `AutoLibrary`: the id
 * protocol and the root node are on the companion precisely because they need no repository, which is what
 * makes them testable at all.
 */
@RunWith(RobolectricTestRunner::class)
class AutoLibraryTest {

    @Test
    fun `a book id resolves to that book, from wherever its progress is`() {
        val target = AutoLibrary.resolve("book/tidewatch")

        assertEquals(LibraryItemId("tidewatch"), target?.bookId)
        assertNull(target?.startAt, "a book row carries no position of its own")
    }

    @Test
    fun `a positioned id resolves to the book and the position`() {
        val target = AutoLibrary.resolve("at/tidewatch/600000")

        assertEquals(LibraryItemId("tidewatch"), target?.bookId)
        assertEquals(10.minutes, target?.startAt)
    }

    /**
     * The one that would only ever break in a car.
     *
     * An Audiobookshelf item id is opaque, and nothing in the API says it cannot contain a slash. Splitting
     * on the *first* separator would send a driver to a book that does not exist; splitting on the last
     * keeps the id whole.
     */
    @Test
    fun `a book id containing a slash survives the round trip`() {
        val target = AutoLibrary.resolve("at/li/brary/tidewatch/90000")

        assertEquals(LibraryItemId("li/brary/tidewatch"), target?.bookId)
        assertEquals(90_000L, target?.startAt?.inWholeMilliseconds)
    }

    /** A tab is somewhere to browse, not something to play. */
    @Test
    fun `a browsable node resolves to nothing`() {
        assertNull(AutoLibrary.resolve(AutoLibrary.ROOT))
        assertNull(AutoLibrary.resolve(AutoLibrary.TAB_CONTINUE))
        assertNull(AutoLibrary.resolve(AutoLibrary.TAB_CHAPTERS))
        assertNull(AutoLibrary.resolve(AutoLibrary.TAB_HISTORY))
    }

    /** A malformed id is nothing, not a book at position zero. */
    @Test
    fun `an id's kind names its shape and never its value`() {
        // The whole reason `kindOf` exists is that the id itself may not be logged: an Audiobookshelf item
        // id names a book as surely as its title does (14.5). A kind that leaked any part of the id would
        // defeat the diagnostic it was written for, so this asserts the absence rather than the presence.
        assertEquals("book", AutoLibrary.kindOf("book/tidewatch"))
        assertEquals("at", AutoLibrary.kindOf("at/tidewatch/600000"))
        assertEquals("tab", AutoLibrary.kindOf(AutoLibrary.TAB_CONTINUE))
        assertEquals("root", AutoLibrary.kindOf(AutoLibrary.ROOT))
        assertEquals("notice", AutoLibrary.kindOf(AutoLibrary.NOTICE_EMPTY))
        assertEquals("empty", AutoLibrary.kindOf(""))
        assertEquals("other", AutoLibrary.kindOf("something-else"))

        for (id in listOf("book/tidewatch", "at/tidewatch/600000", AutoLibrary.TAB_CONTINUE)) {
            assertFalse(AutoLibrary.kindOf(id).contains("tidewatch"), "kindOf leaked the id for $id")
        }
    }

    @Test
    fun `every id the car can play has a kind that is not other`() {
        // The property that keeps the log honest. `resolve` decides what plays and `kindOf` describes it;
        // if a later change teaches one about a new id form and not the other, a car tap would be logged as
        // `kind=other` and the next person reading it would chase the wrong thing. This turns that red.
        val playable = listOf("book/tidewatch", "at/tidewatch/600000", "at/li/brary/tidewatch/90000")
        for (id in playable) {
            assertNotNull(AutoLibrary.resolve(id), "$id should resolve")
            assertNotEquals("other", AutoLibrary.kindOf(id), "$id resolves but has no kind")
        }
    }

    @Test
    fun `an id that does not parse resolves to nothing`() {
        assertNull(AutoLibrary.resolve("at/tidewatch/not-a-number"))
        assertNull(AutoLibrary.resolve("at/tidewatch"))
        assertNull(AutoLibrary.resolve("at//600000"))
        assertNull(AutoLibrary.resolve("something-else"))
    }
}
