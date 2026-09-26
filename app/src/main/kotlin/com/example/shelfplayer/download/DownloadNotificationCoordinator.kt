package com.example.shelfplayer.download

import com.example.shelfplayer.core.model.download.durableDownloadProgress
import com.example.shelfplayer.domain.download.DownloadExecutionKey
import com.example.shelfplayer.domain.download.DownloadExecutionObserver
import com.example.shelfplayer.domain.download.DownloadRecoveryPolicy
import com.example.shelfplayer.domain.download.DownloadRecoveryState
import com.example.shelfplayer.domain.repository.DownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #29 — keeps non-running download notifications truthful across process reconstruction and WorkManager
 * retries. Room remains physical truth; WorkManager remains transient execution truth.
 */
@Singleton
class DownloadNotificationCoordinator @Inject constructor(
    private val downloads: DownloadRepository,
    private val execution: DownloadExecutionObserver,
    private val notifications: DownloadNotificationFactory,
) {
    private var started = false
    private var posted = emptySet<DownloadExecutionKey>()

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch {
            downloads.observeAll()
                .flatMapLatest { stored ->
                    val keys = stored.mapTo(linkedSetOf()) { DownloadExecutionKey(it.serverId, it.itemId) }
                    execution.observe(keys).map { transient -> stored to transient }
                }
                .collect { (stored, transient) ->
                    val desired = linkedSetOf<DownloadExecutionKey>()
                    stored.forEach { book ->
                        val key = DownloadExecutionKey(book.serverId, book.itemId)
                        val snapshot = transient[key]
                        val recovery = DownloadRecoveryPolicy.resolve(
                            durableState = book.state,
                            manifestFilesComplete = book.isComplete,
                            safeFailureSummary = null,
                            executionEvidence = snapshot?.evidence,
                        ).state
                        if (recovery in ACTIVE_NOTIFICATION_STATES) {
                            desired += key
                            notifications.post(
                                serverId = book.serverId,
                                itemId = book.itemId,
                                state = recovery,
                                progress = snapshot?.progress ?: book.durableDownloadProgress(),
                            )
                        }
                    }

                    (posted - desired).forEach { key ->
                        notifications.cancel(key.serverId, key.itemId)
                    }
                    posted = desired
                }
        }
    }

    private companion object {
        val ACTIVE_NOTIFICATION_STATES = setOf(
            DownloadRecoveryState.Queued,
            DownloadRecoveryState.Waiting,
            DownloadRecoveryState.Running,
            DownloadRecoveryState.Retrying,
        )
    }
}
