package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.LibraryItemId
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
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
    fun `an id that does not parse resolves to nothing`() {
        assertNull(AutoLibrary.resolve("at/tidewatch/not-a-number"))
        assertNull(AutoLibrary.resolve("at/tidewatch"))
        assertNull(AutoLibrary.resolve("at//600000"))
        assertNull(AutoLibrary.resolve("something-else"))
    }

    @Test
    fun `the root is browsable and not playable`() {
        val root = AutoLibrary.root()

        assertEquals(AutoLibrary.ROOT, root.mediaId)
        assertEquals(true, root.mediaMetadata.isBrowsable)
        assertEquals(false, root.mediaMetadata.isPlayable)
    }
}
