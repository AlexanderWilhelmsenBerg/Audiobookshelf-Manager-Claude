package com.example.shelfplayer.data.downloads

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ServerId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serializes claim creation and destructive physical-copy removal for one (server, item) inside this process.
 *
 * Room can make claim mutations atomic, and the filesystem can make one rename atomic, but neither can make
 * a database claim and a recursive directory delete one atomic operation. Sharing this keyed lock between
 * [DefaultDownloadRepository.request] and [BookDownloader.remove] closes the window where a second profile
 * could claim a copy after the last-claim check but before its bytes were deleted.
 */
@Singleton
class DownloadCopyLocks @Inject constructor() {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withLock(serverId: ServerId, itemId: LibraryItemId, block: suspend () -> T): T {
        val key = serverId.value + KEY_SEPARATOR + itemId.value
        return locks.getOrPut(key, ::Mutex).withLock { block() }
    }

    private companion object {
        const val KEY_SEPARATOR = "\u001f"
    }
}
