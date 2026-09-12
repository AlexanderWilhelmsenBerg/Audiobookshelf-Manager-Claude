package com.example.shelfplayer.playback

import androidx.media3.common.MediaItem
import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.domain.repository.RememberedBookRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BW-PLAY-01 — turns actual local audio into durable device ownership.
 *
 * Called from the player-is-playing callback, not from session-open or library sync. A paused startup restore
 * therefore cannot claim ownership, and REST/realtime progress has no path to this write.
 */
@Singleton
class RememberedBookRecorder private constructor(
    private val record: (ProfileId, LibraryItemId) -> Unit,
) {
    @Inject
    constructor(
        rememberedBooks: RememberedBookRepository,
        @param:ApplicationScope applicationScope: CoroutineScope,
    ) : this(
        record = { profileId, bookId ->
            // Start the DataStore update before returning to the main-thread callback. Profile deletion can
            // then enqueue its clear after this write instead of letting a late write recreate deleted state.
            // DataStore performs the I/O asynchronously; this establishes order only to the first suspension.
            applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
                rememberedBooks.remember(profileId, bookId)
            }
        },
    )

    fun onPlaying(mediaItem: MediaItem?) {
        val item = mediaItem ?: return
        val profileId = MediaItems.ownerOf(item) ?: return
        record(profileId, MediaItems.bookIdOf(item))
    }

    companion object {
        /** Tests unrelated to local ownership can retain their existing coordinator construction. */
        val None = RememberedBookRecorder { _, _ -> }
    }
}
