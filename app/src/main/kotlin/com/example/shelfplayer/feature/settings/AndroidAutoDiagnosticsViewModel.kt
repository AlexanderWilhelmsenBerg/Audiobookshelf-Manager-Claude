package com.example.shelfplayer.feature.settings

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.playback.AndroidAutoDiagnosticsCapture
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class AndroidAutoDiagnosticsViewModel @Inject constructor(
    private val capture: AndroidAutoDiagnosticsCapture,
    @param:ApplicationContext private val context: Context,
    @param:Dispatcher(ShelfDispatcher.Io) private val io: CoroutineDispatcher,
    private val clock: AppClock,
) : ViewModel() {
    val status = capture.state
    val drives = capture.drives.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)
    private val clearingMutable = MutableStateFlow(false)
    val clearing = clearingMutable.asStateFlow()
    private val exportLock = Mutex()
    private val directory get() = File(context.cacheDir, "drive-exports")

    fun enable(enabled: Boolean) = capture.setEnabled(enabled)

    fun clear() {
        if (!clearingMutable.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                withContext(io + NonCancellable) {
                    exportLock.withLock {
                        capture.clearAndAwait()
                        directory.listFiles()?.forEach(File::delete)
                    }
                }
            } finally {
                clearingMutable.value = false
            }
        }
    }

    /** Freeze the selected records before leaving for DocumentsUI or the Android sharesheet. */
    suspend fun prepare(build: String, selected: Set<Long>): Uri? = withContext(io) {
        if (selected.isEmpty()) return@withContext null
        exportLock.withLock {
            try {
                val report = capture.report(build, selected)
                val now = clock.now()
                directory.mkdirs()
                val cutoff = now.minusSeconds(SHARE_RETENTION_SECONDS).toEpochMilli()
                directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach(File::delete)
                val prefix = UnifiedDiagnosticsFileExport.androidAutoFilename(now).removeSuffix(".json")
                val file = File.createTempFile("$prefix-", ".json", directory)
                UnifiedDiagnosticsFileExport.write(file.outputStream(), report)
                FileProvider.getUriForFile(context, "${context.packageName}.drive-exports", file)
            } catch (_: IOException) {
                null
            }
        }
    }

    suspend fun save(uri: Uri, source: Uri): Boolean = withContext(io) {
        try {
            context.contentResolver.openInputStream(source)?.use { input ->
                context.contentResolver.openOutputStream(uri, "wt")?.use { output -> input.copyTo(output) }
            } != null
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private companion object {
        const val SHARE_RETENTION_SECONDS = 86_400L
    }
}
