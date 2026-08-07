package com.example.shelfplayer.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.domain.sync.BackgroundSync
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC SYNC-003 — [BackgroundSync] against WorkManager.
 *
 * ### Six hours, not fifteen minutes
 *
 * Android's floor for periodic work is fifteen minutes and this deliberately sits far above it. A
 * library sync is an N+1 over every item; running it four times an hour on a phone would cost more
 * battery than the feature is worth, and the foreground already covers the case the user is actually
 * present for. This is the safety net for "the app has not been opened in a while", and a safety net
 * does not need to be tight.
 *
 * ### Constraints
 *
 * A connection is required, because a sync without one is a guaranteed failure that still costs a
 * wake-up. Battery-not-low is required for the same reason PRODUCT_SPEC SYNC-003 mentions battery
 * policy at all: a background convenience must never be the reason a phone dies before its owner gets
 * home.
 *
 * Deliberately *not* unmetered-only. A position played on another device is a few hundred bytes, and a
 * user who opens the app on mobile after two days should not find it stale because the sync was
 * waiting for Wi-Fi. The download policy that *does* care about metering is PRODUCT_SPEC DL-004's, and
 * it governs media rather than metadata.
 */
@Singleton
class WorkManagerBackgroundSync @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val logger: Logger,
) : BackgroundSync {

    override suspend fun schedule(profileId: ProfileId) {
        val request = PeriodicWorkRequestBuilder<LibrarySyncWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setInputData(Data.Builder().putString(LibrarySyncWorker.KEY_PROFILE_ID, profileId.value).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            LibrarySyncWorker.nameFor(profileId),
            // KEEP, not UPDATE. Every sign-in and every profile switch calls this, and replacing the
            // request each time would reset the interval — a user who switches accounts twice a day
            // would push the next run permanently into the future and never see a background sync.
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        logger.info(
            LogCategory.Sync,
            "Scheduled the background refresh",
            LogField.Identifier("profile", profileId.value),
            LogField.Count("intervalHours", INTERVAL_HOURS.toInt()),
        )
    }

    override suspend fun cancel(profileId: ProfileId) {
        WorkManager.getInstance(context).cancelUniqueWork(LibrarySyncWorker.nameFor(profileId))
        logger.info(
            LogCategory.Sync,
            "Cancelled the background refresh",
            LogField.Identifier("profile", profileId.value),
        )
    }

    private companion object {
        const val INTERVAL_HOURS = 6L
        const val BACKOFF_MINUTES = 15L
    }
}
