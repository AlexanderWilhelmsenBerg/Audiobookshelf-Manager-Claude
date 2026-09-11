package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

class FreshnessHistoryTest {

    @Test
    fun `remote progress is recorded only after an adopted seek actually resumes`() {
        val plan = adoptPlan()

        assertEquals(PlaybackEvent.RemoteProgress, plan.remoteProgressHistoryEvent(ResumeOutcome.Resumed))
        assertNull(plan.remoteProgressHistoryEvent(ResumeOutcome.SeekLost))
        assertNull(plan.remoteProgressHistoryEvent(ResumeOutcome.Superseded))
        assertNull(plan.remoteProgressHistoryEvent(ResumeOutcome.NotLoaded))
        assertNull(plan.remoteProgressHistoryEvent(ResumeOutcome.WrongBook))
    }

    @Test
    fun `the REST decision row is independent of adoption success`() {
        val plan = adoptPlan()

        assertEquals(PlaybackEvent.ServerCheckAhead, plan.serverCheckHistoryEvent())
    }

    @Test
    fun `restoring a verified baseline is not recorded as remote progress`() {
        val plan = restoredBaselinePlan()

        assertEquals(PlaybackEvent.ServerCheckCurrent, plan.serverCheckHistoryEvent())
        assertNull(plan.remoteProgressHistoryEvent(ResumeOutcome.Resumed))
    }

    private fun adoptPlan() = ResumeFreshnessPlan(
        requestGeneration = 7,
        bookId = LibraryItemId("book-a"),
        profileId = ProfileId("profile-a"),
        baselineGeneration = 5,
        localPosition = 10.minutes,
        decision = ResumeFreshnessDecision.Adopt(
            position = 20.minutes,
            source = FreshnessEvidenceSource.Rest,
        ),
    )

    private fun restoredBaselinePlan() = ResumeFreshnessPlan(
        requestGeneration = 8,
        bookId = LibraryItemId("book-a"),
        profileId = ProfileId("profile-a"),
        baselineGeneration = 6,
        localPosition = kotlin.time.Duration.ZERO,
        decision = ResumeFreshnessDecision.Adopt(
            position = 20.minutes,
            source = FreshnessEvidenceSource.RestoredBaseline,
        ),
    )
}
