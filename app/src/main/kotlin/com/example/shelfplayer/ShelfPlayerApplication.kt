package com.example.shelfplayer

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.data.auth.SessionRestorer
import com.example.shelfplayer.domain.download.OfflineFiles
import com.example.shelfplayer.domain.download.OfflineVerification
import com.example.shelfplayer.domain.repository.SleepTimerRepository
import com.example.shelfplayer.domain.usecase.ApplyStartupModeUseCase
import com.example.shelfplayer.domain.usecase.CleanUpDownloadsUseCase
import com.example.shelfplayer.lock.ProcessLockWatcher
import com.example.shelfplayer.playback.AutoLibrary
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PRODUCT_SPEC 9.4 — `:app` performs the final dependency-injection wiring.
 *
 * The session restore is launched into the injected [ApplicationScope] rather than `GlobalScope`
 * (PRODUCT_SPEC 22.10) and is deliberately not awaited: `Application.onCreate` runs on the main thread
 * and blocking it on a Keystore decryption is how an app earns a cold-start ANR. Screens observe Room and
 * the active profile, so they render as soon as there is anything to render.
 *
 * This used to seed the bundled demo library. It does not any more — the app talks to a real server now,
 * and a fixture library written into the same tables as real content would be indistinguishable from it.
 */
@HiltAndroidApp
class ShelfPlayerApplication :
    Application(),
    Configuration.Provider {
    /**
     * PRODUCT_SPEC SYNC-003 — WorkManager builds our workers, so it needs a factory that can inject
     * them. The manifest removes the default initializer, because two initialisations race.
     */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    @Inject
    lateinit var sessionRestorer: SessionRestorer

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    /**
     * PRODUCT_SPEC PLAY-008 — closes sleep timers a previous process left running.
     *
     * A timer running when the app died has no recorded end, and leaving it open would make the history
     * show a timer that is not running for as long as the install lives.
     */
    @Inject
    lateinit var sleepTimers: SleepTimerRepository

    /**
     * PRODUCT_SPEC DL-001 — removes downloaded files that no manifest claims any more.
     *
     * At start-up, once, because that is the only moment at which nothing is writing to the download
     * directory. A `.part` is deliberately kept after a failure — it is what a retry resumes from — and
     * that stops being true when the manifest goes: an item deleted upstream, or a crash between the bytes
     * and the row, leaves files nothing will ever ask for and nothing else would ever find.
     */
    @Inject
    lateinit var offlineFiles: OfflineFiles

    /**
     * PRODUCT_SPEC DL-002 — the incremental verifier, and DL-006's retention pass.
     *
     * Both at start-up, in that order, and both are no-ops until they have something to do: the verifier
     * `stat`s the files a manifest claims, and the cleanup returns immediately unless the user turned a
     * retention on. Verifying first matters — a book whose files went missing must be marked incomplete
     * before anything considers deleting it, or the cleanup would remove a manifest for files that are
     * already gone and lose the record that they ever existed.
     */
    @Inject
    lateinit var verification: OfflineVerification

    @Inject
    lateinit var cleanUpDownloads: CleanUpDownloadsUseCase

    /**
     * PRODUCT_SPEC ROUTE-003 — what opening the app does to the player.
     *
     * Here rather than in an activity or a ViewModel because "opening the app" means *this process
     * starting*. `onCreate` runs once; a ViewModel is rebuilt on every rotation and an activity's
     * `onResume` fires every time somebody comes back from a message — and a listener who paused, checked
     * a notification and returned must not find their book restarted.
     */
    @Inject
    lateinit var applyStartupMode: ApplyStartupModeUseCase

    @Inject
    lateinit var auto: AutoLibrary

    /**
     * AUTH-005 — stamps the lock gate when the app leaves the foreground.
     *
     * Registered here because the gate follows the *process*, not any one screen, and because without it
     * the relock delay never fires at all — see `ProcessLockWatcher`.
     */
    @Inject
    lateinit var lockWatcher: ProcessLockWatcher

    @Inject
    lateinit var logger: Logger

    override fun onCreate() {
        super.onCreate()
        logger.info(LogCategory.App, "Application started")
        lockWatcher.attach(this)
        applicationScope.launch {
            sessionRestorer.restoreActiveSession()
            // PRODUCT_SPEC ROUTE-003 — after the session is restored, because arming a book needs a signed-in
            // profile to open a session for. Does nothing at all in the default mode.
            applyStartupMode(auto.lastPlayed()?.id)
        }
        applicationScope.launch {
            sleepTimers.closeOrphanedSessions()
        }
        applicationScope.launch {
            offlineFiles.sweepOrphans()
            verification.verifyManifests()
            // No book id: nothing is playing at process start, so the "never the playing book" rule has
            // nothing to exclude. A cleanup that ran mid-session would need one.
            cleanUpDownloads()
        }
    }
}
