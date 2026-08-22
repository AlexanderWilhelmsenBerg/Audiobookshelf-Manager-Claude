package com.example.shelfplayer.lock

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.example.shelfplayer.domain.lock.ProfileLockGate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AUTH-005 — tells [ProfileLockGate] when the app leaves and re-enters the foreground.
 *
 * ### Without this, the relock delay does not exist
 *
 * The gate evaluates a ticket against `backgroundedAt`, and nothing was ever stamping it. The
 * consequence was not subtle and not partial: `isUnlocked` returned `true` for the whole life of the
 * process, so a profile unlocked once stayed unlocked until Android killed the app. All three relock
 * options — **including `Immediately`** — behaved identically, which is to say not at all. The lock
 * engaged on a cold start and never again.
 *
 * `ProfileLockGateTest` covered the arithmetic in full and passed, because the arithmetic was right. What
 * was missing was a caller, and a unit test of a class cannot notice that nothing constructs it. That is
 * the same shape as R-37 and it is recorded again as R-43.
 *
 * ### Why an activity counter and not `ProcessLifecycleOwner`
 *
 * `androidx.lifecycle:lifecycle-process` is the library built for exactly this and it is **not** in the
 * version catalog. Adding it would mean regenerating `verification-metadata.xml` under
 * `org.gradle.dependency.verification=strict`, for a class this app can write in thirty lines. The
 * counter is the mechanism `ProcessLifecycleOwner` itself uses.
 *
 * ### The configuration-change guard is the whole difficulty
 *
 * A rotation stops the old activity before starting the new one, so a naive counter reaches zero and
 * reports the app as backgrounded. With the default relock delay that would lock the app **every time the
 * phone was turned sideways**. [Activity.isChangingConfigurations] distinguishes the two, and it is
 * checked at `onStop` where the framework has already set it.
 *
 * This app declares `configChanges` for orientation, so in practice the activity is not recreated at all —
 * but relying on a manifest attribute to keep a security control correct is how the attribute gets removed
 * two years later by somebody who does not know what it was holding up.
 */
@Singleton
class ProcessLockWatcher @Inject constructor(private val gate: ProfileLockGate) :
    Application.ActivityLifecycleCallbacks {

    private var startedActivities = 0

    fun attach(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        val wasBackground = startedActivities == 0
        startedActivities++
        if (wasBackground) gate.onForegrounded()
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        // See the class KDoc: a rotation passes through zero, and must not be read as leaving the app.
        if (startedActivities == 0 && !activity.isChangingConfigurations) gate.onBackgrounded()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
