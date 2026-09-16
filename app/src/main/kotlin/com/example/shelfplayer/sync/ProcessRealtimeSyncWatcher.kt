package com.example.shelfplayer.sync

import android.app.Activity
import android.app.Application
import android.os.Bundle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC SYNC-002 / SYNC-003 — translates process foreground/background into realtime ownership.
 *
 * This mirrors the app's existing process-lock watcher rather than adding a new lifecycle dependency. A
 * configuration change is deliberately ignored so rotating/recreating an activity does not churn the socket.
 */
@Singleton
class ProcessRealtimeSyncWatcher @Inject constructor(private val coordinator: ForegroundRealtimeSyncCoordinator) :
    Application.ActivityLifecycleCallbacks {
    private var startedActivities = 0

    fun attach(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        val wasBackground = startedActivities == 0
        startedActivities++
        if (wasBackground) coordinator.onForegrounded()
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0 && !activity.isChangingConfigurations) coordinator.onBackgrounded()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
