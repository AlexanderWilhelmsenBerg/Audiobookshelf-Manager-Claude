package com.example.shelfplayer.data.downloads

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.storage.StorageManager
import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.download.DownloadStorageState
import com.example.shelfplayer.core.model.download.StorageVolumeOption
import com.example.shelfplayer.core.model.getOrNull
import com.example.shelfplayer.core.model.resultOf
import com.example.shelfplayer.domain.download.DownloadLocations
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC DL-003 / ADR-0018 decision 4 / ADR-0020 — the volumes downloads can be written to.
 *
 * ### App-specific directories, not the Storage Access Framework
 *
 * `Context.getExternalFilesDirs` returns this app's own directory on **every** mounted volume, including an
 * SD card. Those are ordinary `File` paths that need no permission and no user grant, so the whole download
 * pipeline — the `.part`, the verify, the atomic rename, the sweep — works on them unchanged. Choosing an
 * arbitrary folder needs SAF, where paths do not exist at all; ADR-0020 records why that half is not here.
 *
 * ### The choice is a UUID and the answer is a path
 *
 * A removable volume's mount path is not stable across reboots, so the setting stores what the volume *is*
 * and this resolves it to where it currently *is* — every time it is asked, because a card can be removed
 * between one file and the next. A choice that no longer resolves falls back to internal storage rather
 * than failing: new downloads land somewhere real, and existing ones are unaffected because the manifest
 * holds their absolute locations.
 */
@Singleton
class StorageVolumes @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: AppSettingsDataSource,
    @ApplicationScope scope: CoroutineScope,
    @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
) : DownloadLocations,
    DownloadRoots {

    /**
     * The chosen volume's UUID, kept hot.
     *
     * `DownloadStorage` is called from non-suspending code — `partFor`, `commit`, `usableBytes` — so the
     * setting has to be readable without suspending. A `StateFlow` started eagerly in the application scope
     * is that: one collector for the life of the process, and a value that is already correct by the time
     * any download runs.
     */
    private val chosen: StateFlow<String> = settings.downloadVolumeUuid.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = StorageVolumeOption.INTERNAL_UUID,
    )

    /**
     * Every root the app can see, with the one new downloads go to first.
     *
     * The rest are here because the manifest holds absolute locations: a book downloaded before the volume
     * changed is still on the old root, and a delete or a sweep that only looked at the current one would
     * leave it there permanently — nothing else in the app can find it either.
     */
    override fun roots(): List<File> {
        val current = rootForVolume(destinationVolumeUuid()) ?: context.filesDir
        val others = candidates().filter { it.absolutePath != current.absolutePath }
        return listOf(current) + others
    }

    override fun destinationVolumeUuid(): String =
        chosen.value.takeIf { selected -> selected.isNotEmpty() && rootForVolume(selected) != null }
            ?: StorageVolumeOption.INTERNAL_UUID

    override fun rootForVolume(uuid: String?): File? = when {
        uuid == null -> null

        uuid == StorageVolumeOption.INTERNAL_UUID -> context.filesDir

        else -> {
            val manager = context.getSystemService(StorageManager::class.java) ?: return null
            context.getExternalFilesDirs(null)
                .filterNotNull()
                .firstOrNull { directory -> manager.getStorageVolume(directory)?.uuid == uuid }
                ?.takeIf { it.exists() || it.mkdirs() }
        }
    }

    override fun availableVolumeUuids(): Set<String> {
        val manager = context.getSystemService(StorageManager::class.java)
        return buildSet {
            add(StorageVolumeOption.INTERNAL_UUID)
            context.getExternalFilesDirs(null).filterNotNull().forEach { directory ->
                manager?.getStorageVolume(directory)?.uuid?.takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    /**
     * PRODUCT_SPEC SET-002 — the list the settings screen offers.
     *
     * Internal storage is always first and always present. A volume the platform reports but that has no
     * app directory on it is left out: it is not somewhere this app can write, so offering it would be
     * offering a choice that silently does nothing.
     */
    override suspend fun options(): List<StorageVolumeOption> = withContext(ioDispatcher) {
        val manager = context.getSystemService(StorageManager::class.java)
        candidates().mapNotNull { directory ->
            val volume = manager?.getStorageVolume(directory)
            val uuid = volume?.uuid.orEmpty().takeIf { directory != context.filesDir }.orEmpty()
            // Two directories on one volume would produce two identical rows. Only the internal one has an
            // empty uuid, so anything else without one cannot be told apart and is not offered.
            if (directory != context.filesDir && uuid.isEmpty()) return@mapNotNull null
            StorageVolumeOption(
                uuid = uuid,
                label = volume?.getDescription(context).orEmpty().ifBlank { directory.name },
                freeBytes = allocatableBytes(manager, directory),
                isRemovable = volume?.isRemovable == true,
            )
        }
    }

    override fun observeSelected(): Flow<String> = settings.downloadVolumeUuid

    override fun observeAvailableVolumeUuids(): Flow<Set<String>> = callbackFlow {
        fun publish() {
            trySend(availableVolumeUuids())
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                publish()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        publish()
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }.distinctUntilChanged()

    override fun availability(volumeUuid: String?): DownloadStorageState = when {
        volumeUuid == null -> DownloadStorageState.Unknown
        volumeUuid == StorageVolumeOption.INTERNAL_UUID -> DownloadStorageState.Available
        rootForVolume(volumeUuid) != null -> DownloadStorageState.Available
        else -> DownloadStorageState.Unavailable
    }

    override suspend fun select(uuid: String): AppResult<Unit> = resultOf {
        settings.setDownloadVolumeUuid(uuid)
    }

    /**
     * PRODUCT_SPEC DL-001 — **allocatable** bytes, the same reading `DownloadStorage` checks against.
     *
     * Android holds a large evictable cache, so `usableSpace` on a nearly-full phone understates what a
     * download can actually have — sometimes by gigabytes. Showing that number here and checking a
     * different one before queuing would produce a picker that says a volume is full and a download that
     * then succeeds on it.
     */
    private fun allocatableBytes(manager: StorageManager?, directory: File): Long = resultOf {
        checkNotNull(manager).getAllocatableBytes(manager.getUuidForPath(directory))
    }.getOrNull() ?: directory.usableSpace.coerceAtLeast(0)

    /** Internal first, then this app's directory on every other mounted volume. */
    private fun candidates(): List<File> = buildList {
        add(context.filesDir)
        // `getExternalFilesDirs` can contain nulls for volumes that are present but not mounted.
        context.getExternalFilesDirs(null).filterNotNull().forEach(::add)
    }
}
