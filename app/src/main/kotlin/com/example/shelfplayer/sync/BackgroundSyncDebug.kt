package com.example.shelfplayer.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.example.shelfplayer.core.model.ProfileId
import java.util.concurrent.TimeUnit

/**
 * Debug-only UI support for exercising the real [LibrarySyncWorker] without waiting for the six-hour
 * periodic schedule or needing adb.
 *
 * This intentionally uses a different unique-work name from [LibrarySyncWorker.nameFor], so running or
 * cancelling a test cannot replace, reset or cancel the production periodic refresh. It also omits the
 * production scheduling constraints: pressing a test button means "run the worker now", which makes both
 * offline/network-failure and server-failure paths directly observable. The worker itself, its repositories
 * and its retry policy are unchanged.
 */
internal fun enqueueBackgroundSyncTest(context: Context, profileId: ProfileId) {
    val request = OneTimeWorkRequestBuilder<LibrarySyncWorker>()
        .setInputData(
            Data.Builder()
                .putString(LibrarySyncWorker.KEY_PROFILE_ID, profileId.value)
                .build(),
        )
        // WorkManager's minimum keeps a retry test quick while still exercising Result.retry() for real.
        .setBackoffCriteria(
            BackoffPolicy.EXPONENTIAL,
            WorkRequest.MIN_BACKOFF_MILLIS,
            TimeUnit.MILLISECONDS,
        )
        .build()

    WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
        debugBackgroundSyncNameFor(profileId),
        ExistingWorkPolicy.REPLACE,
        request,
    )
}

internal fun cancelBackgroundSyncTest(context: Context, profileId: ProfileId) {
    WorkManager.getInstance(context.applicationContext).cancelUniqueWork(debugBackgroundSyncNameFor(profileId))
}

internal fun debugBackgroundSyncNameFor(profileId: ProfileId): String = "debug-library-sync-${profileId.value}"
