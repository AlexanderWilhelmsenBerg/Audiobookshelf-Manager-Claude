package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.playback.StartupMode
import com.example.shelfplayer.domain.playback.StartupPlayer
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * PRODUCT_SPEC ROUTE-003 — what opening the app does to the player.
 *
 * ### It runs once per launch, and only on a cold one
 *
 * The caller invokes it when the app process starts, not on every trip through `onResume`. Coming back to
 * the app from the background is not "opening" it — a listener who paused, checked a message and returned
 * would otherwise find their book restarted, which is the failure this ordering exists to avoid.
 *
 * ### Doing nothing is the default and is not a no-op
 *
 * [StartupMode.OnMediaCommand] deliberately touches nothing: no service started, no notification posted, no
 * session claimed. That is what makes it safe as a default, and it is why the app has to *choose* to do
 * nothing rather than simply not having this code.
 */
class ApplyStartupModeUseCase @Inject constructor(
    private val settings: PlaybackSettingsRepository,
    private val player: StartupPlayer,
    private val logger: Logger,
) {

    /**
     * @param lastPlayed the book to restore, or `null` when this profile has never played one. Passed in
     *   rather than read, because "what was last played" is a catalogue question this use case has no
     *   business asking twice — the caller already has the answer for its own screen.
     */
    suspend operator fun invoke(lastPlayed: LibraryItemId?) {
        val mode = settings.observeSettings().first().startupMode
        if (mode == StartupMode.OnMediaCommand) return
        val bookId = lastPlayed ?: return

        when (mode) {
            // ROUTE-003 — "restore last item paused". A media notification appears; nothing makes a sound.
            StartupMode.RestorePaused -> {
                logger.info(LogCategory.Playback, "The app opened and the last book was restored, paused")
                player.arm(bookId)
            }

            // The one startup path that produces audio, and it is never a default: ROUTE-003 says "app
            // launch alone never starts playback by default", so this is only ever reached from a choice.
            StartupMode.ResumeOnOpen -> {
                logger.info(LogCategory.Playback, "The app opened and the last book resumed by choice")
                player.play(bookId)
            }

            StartupMode.OnMediaCommand -> Unit
        }
    }
}
