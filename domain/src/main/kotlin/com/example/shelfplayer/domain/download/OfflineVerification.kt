package com.example.shelfplayer.domain.download

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.download.VerificationReport

/**
 * PRODUCT_SPEC DL-002 — checking that what the manifest claims is still on disk.
 *
 * Two levels because they cost different amounts and answer at different moments. [verifyManifests] is a
 * `stat` per file and runs at every launch; [verifyFully] opens each file as media and runs when somebody
 * presses a button in diagnostics.
 *
 * Neither deletes anything. A file that fails is marked so its book reads as incomplete and offers a
 * retry, which is DL-002's "removed only after user-visible confirmation" and PLAY-003's "a missing local
 * part prevents a false downloaded state".
 */
interface OfflineVerification {

    /** The cheap check: every committed file exists and is the length the manifest recorded. */
    suspend fun verifyManifests(): AppResult<VerificationReport>

    /** PRODUCT_SPEC SET-002 — the same, plus opening each file as media. */
    suspend fun verifyFully(): AppResult<VerificationReport>
}
