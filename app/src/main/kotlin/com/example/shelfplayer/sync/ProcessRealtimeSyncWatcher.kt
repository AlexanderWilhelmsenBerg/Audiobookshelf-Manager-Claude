package com.example.shelfplayer.sync

import android.app.Activity
import android.app.Application
import android.os.Bundle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns Android activity starts/stops into one process-foreground signal for realtime synchronization.
 *
 * This mirrors [com.example.shelfplayer.lock.ProcessLockWatcher]'s activity-counter mechanism so BookWave
 * does not add a lifecycle-process dependency solely to observe a fact Android already exposes. A
 * configuration change is not a trip to the background, and multiple activities still represent one
 * foreground process.
 */
@Singleton
class ProcessRealtimeSyncWatcher @Inject constructor(
    private val coordinator: ForegroundRealtimeSyncCoordinator,
) : Application.ActivityLifecycleCallbacks {
    private var startedActivities = 0

    fun attach(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        if (startedActivities++ == 0) coordinator.onForegrounded()
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity.isChangingConfigurations) return
        if (startedActivities == 0) return
        startedActivities--
        if (startedActivities == 0) coordinator.onBackgrounded()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
