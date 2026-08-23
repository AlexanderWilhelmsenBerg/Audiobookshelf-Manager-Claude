package com.example.shelfplayer.feature.browse

import androidx.core.net.toUri
import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Author
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** LIB-001 / LIB-002 — portrait URLs follow synchronized metadata and never gain a credential query. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthorUrlsTest {

    @Test
    fun `confirmed portrait uses one encoded path segment and the real server revision`() {
        val server = ServerId("server-1")
        val author = Author(
            serverId = server,
            id = AuthorId("author/one + two"),
            name = "Marisol Holt",
            hasPortrait = true,
            remoteUpdatedAt = Instant.ofEpochMilli(1_725_000_000_123L),
        )
        val resolved = assertNotNull(
            authorUrlsFor(mapOf(server to "https://books.example/abs/"))
                .forAuthor(author),
        )

        val uri = resolved.toUri()
        assertEquals(
            listOf("abs", "api", "authors", "author/one + two", "image"),
            uri.pathSegments,
        )
        assertEquals("1725000000123", uri.getQueryParameter("ts"))
        assertNull(uri.getQueryParameter("token"))
    }

    @Test
    fun `unknown server has no author url`() {
        val resolved = authorUrlsFor(emptyMap())

        assertNull(resolved.forAuthor(author(hasPortrait = true)))
    }

    @Test
    fun `an author without a confirmed portrait never causes an image request`() {
        val author = author(hasPortrait = false)

        assertNull(authorUrlsFor(mapOf(author.serverId to "https://books.example")).forAuthor(author))
    }

    private fun author(hasPortrait: Boolean) = Author(
        serverId = ServerId("missing"),
        id = AuthorId("author-1"),
        name = "Marisol Holt",
        hasPortrait = hasPortrait,
    )
}
