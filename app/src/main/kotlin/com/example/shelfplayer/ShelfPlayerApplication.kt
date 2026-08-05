package com.example.shelfplayer

import android.app.Application
import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.data.library.FixtureLibraryBootstrapper
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PRODUCT_SPEC 9.4 — `:app` performs the final dependency-injection wiring.
 *
 * The demo-library seed is launched into the injected [ApplicationScope] rather than `GlobalScope`
 * (PRODUCT_SPEC 22.10) and is deliberately not awaited: `Application.onCreate` runs on the main
 * thread and blocking it on a database write is how an app earns a cold-start ANR. The UI observes
 * Room, so it renders the moment the seed commits.
 */
@HiltAndroidApp
class ShelfPlayerApplication : Application() {
    @Inject
    lateinit var bootstrapper: FixtureLibraryBootstrapper

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Inject
    lateinit var logger: Logger

    override fun onCreate() {
        super.onCreate()
        logger.info(LogCategory.App, "Application started")
        applicationScope.launch {
            bootstrapper.seedIfNeeded()
        }
    }
}
