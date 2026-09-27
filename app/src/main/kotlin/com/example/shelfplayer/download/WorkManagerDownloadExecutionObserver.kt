package com.example.shelfplayer.download

import android.content.Context
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import com.example.shelfplayer.core.common.connectivity.NetworkMonitor
import com.example.shelfplayer.core.model.download.DownloadProgress
import com.example.shelfplayer.domain.download.DownloadExecutionEvidence
import com.example.shelfplayer.domain.download.DownloadExecutionKey
import com.example.shelfplayer.domain.download.DownloadExecutionObserver
import com.example.shelfplayer.domain.download.DownloadExecutionSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BW-DL-04 / #19 — reconstructs transient download execution truth from WorkManager.
 *
 * New work is observed with one aggregate tag query. Work enqueued by older builds did not carry that tag,
 * so collection performs a bounded one-shot lookup by the already-authoritative unique work name, remembers
 * those UUIDs, then observes all legacy UUIDs with one aggregate query. There is never one long-lived
 * WorkManager observer per download row.
 *
 * Work names and identity tags contain internal server/item identifiers. They are used only for matching and
 * are never logged or surfaced to presentation.
 */
@Singleton
class WorkManagerDownloadExecutionObserver @Inject constructor(
    @param:ApplicationContext context: Context,
    private val network: NetworkMonitor,
) : DownloadExecutionObserver {
    private val workManager = WorkManager.getInstance(context)

    override fun observe(keys: Set<DownloadExecutionKey>): Flow<Map<DownloadExecutionKey, DownloadExecutionSnapshot>> {
        if (keys.isEmpty()) return flowOf(emptyMap())

        val byWorkName = keys.associateBy(::workName)
        val taggedQuery = WorkQuery.fromTags(listOf(BookDownloadWorker.DOWNLOAD_TAG))
        val tagged = workManager.getWorkInfosFlow(taggedQuery)

        return kotlinx.coroutines.flow.flow {
            val legacyOwners = buildMap {
                for ((name, key) in byWorkName) {
                    workManager.getWorkInfosForUniqueWorkFlow(name).first()
                        .filter { info -> BookDownloadWorker.DOWNLOAD_TAG !in info.tags }
                        .forEach { info -> put(info.id, key) }
                }
            }

            val legacy = if (legacyOwners.isEmpty()) {
                flowOf(emptyList())
            } else {
                workManager.getWorkInfosFlow(WorkQuery.fromIds(legacyOwners.keys.toList()))
            }

            emitAll(
                combine(tagged, legacy, network.isOnline, network.isUnmetered) {
                        taggedInfos,
                        legacyInfos,
                        isOnline,
                        isUnmetered,
                    ->
                    reduce(
                        keys = keys,
                        byWorkName = byWorkName,
                        taggedInfos = taggedInfos,
                        legacyInfos = legacyInfos,
                        legacyOwners = legacyOwners,
                        network = DownloadNetworkSnapshot(isOnline = isOnline, isUnmetered = isUnmetered),
                    )
                },
            )
        }
    }

    private fun reduce(
        keys: Set<DownloadExecutionKey>,
        byWorkName: Map<String, DownloadExecutionKey>,
        taggedInfos: List<WorkInfo>,
        legacyInfos: List<WorkInfo>,
        legacyOwners: Map<java.util.UUID, DownloadExecutionKey>,
        network: DownloadNetworkSnapshot,
    ): Map<DownloadExecutionKey, DownloadExecutionSnapshot> {
        val grouped = keys.associateWith { mutableListOf<WorkInfo>() }.toMutableMap()

        taggedInfos.forEach { info ->
            val key = info.tags.asSequence().mapNotNull(byWorkName::get).firstOrNull() ?: return@forEach
            grouped.getValue(key) += info
        }
        legacyInfos.forEach { info ->
            val key = legacyOwners[info.id] ?: return@forEach
            grouped.getValue(key) += info
        }

        return buildMap {
            grouped.forEach { (key, infos) ->
                val selected = infos.distinctBy(WorkInfo::id).maxWithOrNull(
                    compareBy<WorkInfo> { it.executionPriority() }
                        .thenBy { it.generation }
                        .thenBy { it.runAttemptCount }
                        .thenBy { it.id.toString() },
                ) ?: return@forEach
                put(
                    key,
                    DownloadExecutionSnapshot(
                        evidence = classifyDownloadWork(selected, network),
                        progress = selected.downloadProgress(),
                    ),
                )
            }
        }
    }

    private fun workName(key: DownloadExecutionKey): String = BookDownloadWorker.nameFor(key.serverId, key.itemId)
}

internal data class DownloadNetworkSnapshot(val isOnline: Boolean, val isUnmetered: Boolean)

/**
 * Pure #19 mapping. In particular, schedule time does not participate: an ENQUEUED retry can have a future
 * backoff timestamp, but run-attempt history plus the currently-required network is the truthful distinction.
 */
internal data class DownloadWorkSnapshot(
    val state: WorkInfo.State,
    val runAttemptCount: Int,
    val requiredNetworkType: NetworkType,
)

internal fun classifyDownloadWork(info: WorkInfo, network: DownloadNetworkSnapshot): DownloadExecutionEvidence =
    classifyDownloadWork(
        work = DownloadWorkSnapshot(
            state = info.state,
            runAttemptCount = info.runAttemptCount,
            requiredNetworkType = info.constraints.requiredNetworkType,
        ),
        network = network,
    )

internal fun classifyDownloadWork(
    work: DownloadWorkSnapshot,
    network: DownloadNetworkSnapshot,
): DownloadExecutionEvidence = when (work.state) {
    WorkInfo.State.RUNNING -> DownloadExecutionEvidence.Running

    WorkInfo.State.ENQUEUED -> when {
        !network.satisfies(work.requiredNetworkType) -> DownloadExecutionEvidence.Waiting
        work.runAttemptCount > 0 -> DownloadExecutionEvidence.Retrying
        else -> DownloadExecutionEvidence.Queued
    }

    // BLOCKED means prerequisite work, not a violated network constraint. BookWave does not currently chain
    // book workers, but if retained/future WorkInfo reports it we must not lie and call it network waiting.
    WorkInfo.State.BLOCKED -> DownloadExecutionEvidence.Queued

    WorkInfo.State.SUCCEEDED,
    WorkInfo.State.FAILED,
    -> DownloadExecutionEvidence.Finished

    WorkInfo.State.CANCELLED -> DownloadExecutionEvidence.Cancelled
}

private fun DownloadNetworkSnapshot.satisfies(required: NetworkType): Boolean = when (required) {
    NetworkType.NOT_REQUIRED -> true

    NetworkType.CONNECTED -> isOnline

    NetworkType.UNMETERED -> isOnline && isUnmetered

    NetworkType.NOT_ROAMING,
    NetworkType.METERED,
    NetworkType.TEMPORARILY_UNMETERED,
    -> isOnline
}

private fun WorkInfo.downloadProgress(): DownloadProgress? {
    val downloaded = progress.getLong(BookDownloadWorker.KEY_PROGRESS_BYTES, -1L)
    val percent = progress.getInt(BookDownloadWorker.KEY_PROGRESS_PERCENT, -1)
    if (downloaded < 0L || percent !in 0..100) return null

    val total = progress.getLong(BookDownloadWorker.KEY_PROGRESS_TOTAL_BYTES, -1L).takeIf { it > 0L }
    return DownloadProgress(
        downloadedBytes = downloaded,
        totalBytes = total,
        fraction = percent / 100f,
    )
}

private fun WorkInfo.executionPriority(): Int = when (state) {
    WorkInfo.State.RUNNING -> 3

    WorkInfo.State.ENQUEUED,
    WorkInfo.State.BLOCKED,
    -> 2

    WorkInfo.State.SUCCEEDED,
    WorkInfo.State.FAILED,
    WorkInfo.State.CANCELLED,
    -> 1
}
