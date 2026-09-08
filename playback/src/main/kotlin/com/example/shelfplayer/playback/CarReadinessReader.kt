package com.example.shelfplayer.playback

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.model.playback.CarReadiness
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC PLAY-001 / ROUTE-002 — asks the platform the questions Android Auto asks about this app.
 *
 * See [CarReadiness] for why this exists rather than a fix. In short: everything the APK controls is
 * already correct, and the remaining causes of *"the app doesn't show among the apps"* are all on the
 * phone. This makes them visible without a car.
 *
 * It lives in `:playback` for the same reason [NotificationAccessReader] does: the constants it matches on
 * are the media-session platform's, and `:playback` is the module allowed to know about that.
 */
fun interface CarReadinessReader {
    fun read(): CarReadiness
}

/**
 * When a car last connected, shared between the service that sees it and the screen that reports it.
 *
 * In memory, like [com.example.shelfplayer.core.common.log.EventLog] and for the same reason: it is a
 * debugging aid about *this run* of the app, and a persisted "a car connected once in March" would answer a
 * question nobody is asking. The observation that matters is the negative one — the app has been running
 * for a whole drive and no car has ever bound to it — and that only makes sense within a process.
 */
@Singleton
class CarConnections @Inject constructor(private val clock: AppClock) {

    @Volatile
    private var last: Instant? = null

    private val bound = AtomicInteger()

    /** Called from the media session's connect callback, which is any thread Media3 chooses. */
    fun onConnected() {
        last = clock.now()
        bound.incrementAndGet()
    }

    /**
     * PRODUCT_SPEC PLAY-002 — a car controller went away.
     *
     * Counted rather than flagged, because two can be bound at once: Android Auto's projection host and the
     * phone's own companion are separate controllers and both match `PlaybackService`'s car packages. A
     * flag would clear on the first disconnect and take the car button away while a car was still there.
     *
     * Floored at zero. Media3 does not promise a `onDisconnected` for every `onConnect` — a controller whose
     * process dies is reaped, and a service recreated under a bound car sees the disconnect without ever
     * having seen the connect — and a negative count would keep the button hidden for the rest of the run.
     */
    fun onDisconnected() {
        bound.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }

    /** Whether a car is bound to the media session **right now**, as opposed to having been at some point. */
    fun isConnected(): Boolean = bound.get() > 0

    fun lastConnectedAt(): Instant? = last
}

@Singleton
internal class DefaultCarReadinessReader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val connections: CarConnections,
) : CarReadinessReader {

    override fun read(): CarReadiness = CarReadiness(
        isDeclared = isCarAppDeclared(),
        hasBrowserService = hasExportedBrowserService(),
        isAndroidAutoInstalled = isAndroidAutoInstalled(),
        installer = installerPackage(),
        lastConnectedAt = connections.lastConnectedAt(),
    )

    /**
     * Whether the `<application>` carries the metadata Android Auto enumerates media apps by.
     *
     * Read back off the *installed* package rather than trusted from the source manifest. The wave 5 defect
     * was a missing entry, and the check that would have caught it is one that looks at what was installed —
     * a manifest in the repository proves nothing about the APK on the phone.
     */
    private fun isCarAppDeclared(): Boolean = try {
        val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        info.metaData?.containsKey(CAR_APPLICATION_METADATA) == true
    } catch (_: PackageManager.NameNotFoundException) {
        // The app asking about itself, so this is unreachable in practice — but it is a checked outcome of
        // the call, and the honest answer to "could not look it up" is "not declared" rather than a crash in
        // a diagnostics screen. Caught by name: nothing else this line can throw should be hidden.
        false
    }

    /**
     * Whether an exported service in this package answers the browse action a car binds to.
     *
     * Scoped to this package deliberately: `queryIntentServices` across the device would answer "some media
     * app is installed", which is not the question and would report success while this app was invisible.
     */
    private fun hasExportedBrowserService(): Boolean {
        val intent = Intent(BROWSER_SERVICE_ACTION).setPackage(context.packageName)
        return context.packageManager
            .queryIntentServices(intent, 0)
            .any { resolved -> resolved.serviceInfo?.exported == true }
    }

    private fun isAndroidAutoInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(ANDROID_AUTO_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        // The documented way to ask "is this installed". Not an error: a phone without Android Auto is a
        // perfectly ordinary phone, and this is the reading that says so.
        false
    }

    /**
     * Who installed this build, or `null` for a sideload.
     *
     * `getInstallSourceInfo` from API 30, the deprecated call below it. Both raise `NameNotFoundException`
     * for a package mid-replacement, which reads as "we could not tell" — the same as an app with no
     * installer, and rendered the same way, because both mean Android Auto will want *Unknown sources*.
     */
    @Suppress("DEPRECATION")
    private fun installerPackage(): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            context.packageManager.getInstallerPackageName(context.packageName)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private companion object {
        /** The one entry without which Android Auto never lists an app, whatever else it declares. */
        const val CAR_APPLICATION_METADATA = "com.google.android.gms.car.application"

        /** What a car binds to. Media3's `MediaLibraryService` serves it through its legacy stub. */
        const val BROWSER_SERVICE_ACTION = "android.media.browse.MediaBrowserService"

        const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
    }
}
