package com.example.shelfplayer.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.NetworkPolicy
import com.example.shelfplayer.core.model.download.TrafficCategory
import com.example.shelfplayer.domain.download.DownloadScheduler
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC DL-001 / DL-004 / §12 — [DownloadScheduler] against WorkManager.
 *
 * ### The network constraint is the user's setting, not a constant
 *
 * DL-004's default is Wi-Fi only for manual downloads, so the constraint is `UNMETERED` unless the user has
 * turned cellular on for downloads in Settings (ADR-0018 decision 5). WorkManager then holds the job until
 * the network qualifies, which is exactly the requirement's "switching from Wi-Fi to cellular during a
 * disallowed download pauses it" — the platform pauses and resumes it for us, without a wake lock or a
 * poll of our own.
 *
 * A constraint the user cannot see or change would be worse than none, because a download that silently
 * never starts is indistinguishable from a broken button. That is why this landed with the setting rather
 * than before it, and why the book screen reports *waiting for Wi-Fi* rather than showing an idle ring.
 *
 * ### Expedited, with a fallback
 *
 * `setExpedited` asks Android to start now rather than at its convenience. A download the user just pressed
 * is exactly the case expedited work is for, and the quota fallback is a plain job rather than a refusal —
 * running late is better than not running.
 *
 * ### `KEEP`, except when a tap has to overrule a sweep
 *
 * A second tap on a book already downloading must not restart it. `KEEP` makes the second tap a no-op, which
 * is also what makes the use case's "record the claim, then enqueue" safe to call from a second profile.
 *
 * That was unconditionally right while every job carried the same constraint. It stopped being right the
 * moment smart and manual downloads could ask for different ones (DL-004): smart download queues a book as
 * `UNMETERED`, the listener then taps *Download* on a train with manual cellular allowed — and `KEEP` holds
 * the stricter job, so the tap does nothing and the book waits for Wi-Fi the user has already said it does
 * not need. A button that silently obeys a setting the user overrode is the same failure as a button that
 * does not work.
 *
 * So a manual request replaces the work **only when the manual policy is more permissive than the automatic
 * one** — the single case where an existing job could be holding a constraint this request is entitled to
 * relax. Decided from the policy rather than by reading the running job's constraints: it is a pure
 * function, it needs no query, and it cannot race with a job that finishes between the look and the
 * enqueue.
 *
 * Replacing costs little and never costs progress. `BookDownloadWorker` resumes from the `.part` files on
 * disk, so a replaced job continues where the old one stopped rather than starting the book again; what is
 * lost is at most the chunk in flight.
 */
@Singleton
class WorkManagerDownloadScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: PlaybackSettingsRepository,
    private val logger: Logger,
) : DownloadScheduler {

    override suspend fun enqueue(serverId: ServerId, itemId: LibraryItemId, category: TrafficCategory) {
        val policy = settings.observeNetworkPolicy().first()
        // PRODUCT_SPEC DL-004 — the caller's own category, not a constant. This line read
        // `TrafficCategory.ManualDownload` whoever asked, so `smartDownloadsOnCellular` was a setting the
        // user could see, change and store, and that nothing ever read. Its default is `false` and the
        // manual default is `false` too, which is exactly why nobody noticed: the two agreed until somebody
        // turned one of them on.
        val network = networkFor(policy, category)
        val request = OneTimeWorkRequestBuilder<BookDownloadWorker>()
            .setInputData(
                Data.Builder()
                    .putString(BookDownloadWorker.KEY_SERVER_ID, serverId.value)
                    .putString(BookDownloadWorker.KEY_ITEM_ID, itemId.value)
                    .build(),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(network)
                    // Not `setRequiresStorageNotLow`: the use case already checked free space against this
                    // book's own size, which is a better question than Android's device-wide threshold, and
                    // a job blocked by the system one would wait with no way to explain itself.
                    .build(),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            BookDownloadWorker.nameFor(serverId, itemId),
            existingWorkPolicyFor(policy, category),
            request,
        )
        logger.info(
            LogCategory.Sync,
            "A book download was queued",
            LogField.Public("network", network.name),
            LogField.Public("category", category.name),
        )
    }

    override suspend fun cancel(serverId: ServerId, itemId: LibraryItemId) {
        WorkManager.getInstance(context).cancelUniqueWork(BookDownloadWorker.nameFor(serverId, itemId))
        logger.info(LogCategory.Sync, "A book download was cancelled")
    }

    private companion object {
        /**
         * Thirty seconds, doubling. WorkManager's own floor is ten and its default is thirty.
         *
         * A download fails for one of two reasons — the connection went, or the server did — and both are
         * usually over in under a minute. Starting the backoff there means a passing tunnel costs one retry
         * rather than a quarter of an hour of waiting.
         */
        const val BACKOFF_SECONDS = 30L
    }
}

/**
 * PRODUCT_SPEC DL-004 — cellular for this kind of traffic, or Wi-Fi only.
 *
 * File-level and `internal` rather than a private method, for one reason: a decision worth a test should be
 * reachable by one. As a member it could only be exercised by standing up a `Context`, a `WorkManager` and a
 * settings repository to observe a value neither of them affects — which is how a rule this small ends up
 * with no test at all.
 */
internal fun networkFor(policy: NetworkPolicy, category: TrafficCategory): NetworkType =
    if (policy.allowsCellular(category)) NetworkType.CONNECTED else NetworkType.UNMETERED

/**
 * Whether this request may replace work already queued for the same book.
 *
 * Only a *manual* request ever may, and only when the manual policy allows a network the automatic one does
 * not. That is the one configuration in which an existing job can be holding a constraint stricter than this
 * caller is entitled to — see [WorkManagerDownloadScheduler]'s comment on `KEEP`.
 *
 * Deliberately not the mirror image. An automatic request never replaces a manual one: the listener asked
 * for that book, their permission is the more permissive of the two, and a sweep narrowing it would stop a
 * download somebody is waiting for.
 */
internal fun existingWorkPolicyFor(policy: NetworkPolicy, category: TrafficCategory): ExistingWorkPolicy {
    val mayRelax = category == TrafficCategory.ManualDownload &&
        policy.allowsCellular(TrafficCategory.ManualDownload) &&
        !policy.allowsCellular(TrafficCategory.SmartDownload)
    return if (mayRelax) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
}
