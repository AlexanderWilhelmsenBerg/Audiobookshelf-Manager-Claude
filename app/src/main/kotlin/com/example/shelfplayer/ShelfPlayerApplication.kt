package com.example.shelfplayer

import android.app.Application
import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.data.auth.SessionRestorer
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
class ShelfPlayerApplication : Application() {
    @Inject
    lateinit var sessionRestorer: SessionRestorer

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Inject
    lateinit var logger: Logger

    override fun onCreate() {
        super.onCreate()
        logger.info(LogCategory.App, "Application started")
        applicationScope.launch {
            sessionRestorer.restoreActiveSession()
        }
    }
}
