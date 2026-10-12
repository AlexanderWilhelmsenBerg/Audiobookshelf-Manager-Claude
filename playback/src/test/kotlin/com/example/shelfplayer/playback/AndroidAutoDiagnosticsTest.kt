package com.example.shelfplayer.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.example.shelfplayer.core.common.log.LogField
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@OptIn(UnstableApi::class)
class AndroidAutoDiagnosticsTest {

    @Test
    fun `holder fingerprint identifies inert resume item without exposing uri`() {
        val extras = Bundle().apply {
            putBoolean(MediaItems.KEY_RESUME_PLACEHOLDER, true)
            putString(MediaItems.KEY_OWNER_PROFILE_ID, "profile-secret")
        }
        val item = MediaItem.Builder()
            .setMediaId("book-secret")
            .setUri("data:audio/wav;base64,secret")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setExtras(extras)
                    .build(),
            )
            .build()

        val fields = AndroidAutoDiagnostics.itemFields(item)

        assertEquals("holder", fields.public("itemKind"))
        assertEquals("true", fields.public("itemPlaceholder"))
        assertEquals("inert-placeholder", fields.public("itemUriClass"))
        assertTrue(fields.any { it is LogField.Identifier && it.key == "itemId" })
        assertTrue(fields.any { it is LogField.Identifier && it.key == "itemOwner" })
        assertFalse(fields.any { field -> field.toString().contains("data:audio") })
    }

    @Test
    fun `server backed item is classified as playable without logging url value`() {
        val item = MediaItem.Builder()
            .setMediaId("raw-book-secret")
            .setUri("https://private.example/audio/file.m4b?token=secret")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setArtworkUri("https://private.example/cover.jpg".toUri())
                    .build(),
            )
            .build()

        val fields = AndroidAutoDiagnostics.itemFields(item)

        assertEquals("playable-book", fields.public("itemKind"))
        assertEquals("http", fields.public("itemUriClass"))
        assertEquals("true", fields.public("itemHasArtwork"))
        assertFalse(fields.any { field -> field.toString().contains("private.example") })
        assertFalse(fields.any { field -> field.toString().contains("token=secret") })
    }

    @Test
    fun `browse playable keeps browse identity instead of pretending it is opened`() {
        val item = MediaItem.Builder()
            .setMediaId("book/opaque")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build(),
            )
            .build()

        assertEquals("book", AndroidAutoDiagnostics.itemKind(item))
        assertEquals("none", AndroidAutoDiagnostics.uriClass(item))
    }

    @Test
    fun `series and author cards include a redacted request join key and exact browse surface`() {
        val token = "a".repeat(64)
        val item = MediaItem.Builder()
            .setMediaId("series/series-secret")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setArtworkUri("content://com.example.shelfplayer.autoart/$token".toUri())
                    .build(),
            )
            .build()

        val seriesFields = AndroidAutoDiagnostics.artworkOfferFields(AutoLibrary.TAB_SERIES, 0, item)
        val authorsFields = AndroidAutoDiagnostics.artworkOfferFields(AutoLibrary.TAB_AUTHORS, 3, item)
        assertEquals("series", seriesFields.public("surface"))
        assertEquals("authors", authorsFields.public("surface"))
        assertEquals("series", seriesFields.public("itemKind"))
        assertEquals("0", seriesFields.public("index"))
        assertEquals("3", authorsFields.public("index"))
        val ref = seriesFields.filterIsInstance<LogField.Identifier>().single()
        assertEquals("artworkRef", ref.key)
        assertEquals(token, ref.value)
        assertEquals(ref, authorsFields.filterIsInstance<LogField.Identifier>().single())
        assertEquals("series-books", AndroidAutoDiagnostics.artworkSurface("series/private-id"))
        assertEquals("author-books", AndroidAutoDiagnostics.artworkSurface("author/private-id"))
    }

    @Test
    fun `book handoff distinguishes labelled playable books and unlabelled browse rows without leaking text`() {
        val titled = MediaItem.Builder()
            .setMediaId("private-book-id")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Secret Book Title")
                    .setArtist("Secret Author")
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build(),
            ).build()
        val folder = MediaItem.Builder()
            .setMediaId("tab/continue")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .build(),
            ).build()
        val playable = AndroidAutoDiagnostics.metadataPresenceFields(titled)
        val browse = AndroidAutoDiagnostics.metadataPresenceFields(folder)

        assertEquals("true", playable.public("hasTitle"))
        assertEquals("true", playable.public("hasArtist"))
        assertEquals("true", playable.public("isPlayable"))
        assertEquals("false", browse.public("hasTitle"))
        assertEquals("true", browse.public("isBrowsable"))
        assertFalse(playable.any { it.toString().contains("Secret") })
        assertEquals(20, AndroidAutoDiagnostics.MAX_MEDIA_HANDOFF_ITEMS)
    }

    @Test
    fun `missing and nonlocal artwork do not yield a provider correlation id`() {
        val noArt = MediaItem.Builder().setMediaId("book/hidden").build()
        val remote = MediaItem.Builder()
            .setMediaId("book/hidden")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setArtworkUri("https://private.example/cover?token=secret".toUri())
                    .build(),
            )
            .build()
        assertTrue(AndroidAutoDiagnostics.artworkOfferFields(AutoLibrary.TAB_CONTINUE, 0, noArt).isEmpty())
        assertTrue(AndroidAutoDiagnostics.artworkOfferFields(AutoLibrary.TAB_CONTINUE, 0, remote).isEmpty())
        assertEquals(12, AndroidAutoDiagnostics.MAX_ARTWORK_OFFER_EVENTS)
    }

    @Test
    fun `artwork identifier is redacted consistently with the existing event logger`() {
        val token = "a".repeat(64)
        val field = LogField.Identifier("artworkRef", token)
        val redactor = com.example.shelfplayer.core.common.log.DefaultRedactor(
            com.example.shelfplayer.core.common.log.RedactionPolicy.Default,
        )
        val rendered = redactor.render(field)
        assertTrue(rendered.startsWith("#"))
        assertFalse(rendered.contains(token))
        assertEquals(rendered, redactor.render(LogField.Identifier("artworkRef", token)))
    }

    private fun List<LogField>.public(key: String): String =
        filterIsInstance<LogField.Public>().single { field -> field.key == key }.value
}
