package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.domain.library.lastPlayedBook
import com.example.shelfplayer.domain.playback.StartupPlayer
import com.example.shelfplayer.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * PRODUCT_SPEC 6.5 step 6 — *"The new profile's last player state is restored paused."*
 *
 * ### The last step of the switch, and the only optional one
 *
 * `SwitchProfileUseCase` owns steps 2 to 5, and owns them because they are correctness: the outgoing
 * account's position must be written before the context changes, or it lands on the wrong row. This is step
 * 6, and it is a convenience — nothing is lost without it, the listener simply has to find their book again.
 *
 * That difference decides everything about how it is called. The flush is **awaited** inside the switch; this
 * is **not**, because [StartupPlayer.arm] opens a session and AUTH-002 allows the switch 500 ms. A network
 * call inside that budget would make the ordinary case slow to protect the pleasant one.
 *
 * ### It arms, and never plays
 *
 * 6.5.3 says playback pauses by default and 6.5.8 says *"optional continue playing across profile switch is
 * not supported in version 1"*. So this is [StartupPlayer.arm] and there is no path here that reaches
 * `play`. That is also why `ApplyStartupModeUseCase` is the wrong tool despite doing a similar thing on a
 * cold start: it honours `StartupMode`, and `ResumeOnOpen` would start audio on a switch — which both of
 * those clauses forbid.
 *
 * ### Why the profile is a parameter
 *
 * Because the answer differs from "whoever is active" at the moment this matters. It runs immediately after a
 * switch, and naming the profile makes it independent of whether the selection has propagated to every reader
 * yet — the same reasoning that put an explicit owner on the progress and bookmark writes (R-49, R-50).
 */
class RestoreProfilePlaybackUseCase @Inject constructor(
    private val library: LibraryRepository,
    private val player: StartupPlayer,
    private val logger: Logger,
) {

    /**
     * Arms [profileId]'s last unfinished book, or does nothing when it has none.
     *
     * Silent about failure by design. This is a courtesy performed after an action that has already
     * succeeded; a listener who has just switched account does not need to be told that the book they were
     * not asking for could not be loaded (product priority 1 — nothing here may interrupt).
     */
    suspend operator fun invoke(profileId: ProfileId) {
        val books = library.observeAccessibleBooks(profileId).first()
        val book = lastPlayedBook(books) ?: return
        logger.info(LogCategory.Playback, "The account that was switched to had its last book restored, paused")
        player.arm(book.id)
    }
}
