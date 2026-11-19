package com.example.shelfplayer.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.playback.AndroidAutoDiagnosticsCapture
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class AndroidAutoDiagnosticsViewModel @Inject constructor(
    private val capture: AndroidAutoDiagnosticsCapture,
    @param:ApplicationContext private val context: Context,
    @param:Dispatcher(ShelfDispatcher.Io) private val io: CoroutineDispatcher,
) : ViewModel() {
    val status = capture.state
    private val clearingMutable = MutableStateFlow(false)
    val clearing = clearingMutable.asStateFlow()

    fun enable(enabled: Boolean) = capture.setEnabled(enabled)

    fun clear() {
        if (!clearingMutable.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                withContext(NonCancellable) { capture.clearAndAwait() }
            } finally {
                clearingMutable.value = false
            }
        }
    }

    suspend fun save(uri: Uri, build: String): Boolean = withContext(io) {
        try {
            val report = capture.report(build)
            val output = context.contentResolver.openOutputStream(uri, "wt") ?: return@withContext false
            UnifiedDiagnosticsFileExport.write(output, report)
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
