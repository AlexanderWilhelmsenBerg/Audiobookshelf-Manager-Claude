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

    private fun List<LogField>.public(key: String): String =
        filterIsInstance<LogField.Public>().single { field -> field.key == key }.value
}
