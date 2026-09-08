package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.auth.AccountProgress
import com.example.shelfplayer.core.model.playback.AcknowledgedPause
import com.example.shelfplayer.core.model.playback.ExternalSessionCheck
import com.example.shelfplayer.domain.realtime.RealtimeProgressEvidence
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class ResumeFreshnessPolicyTest {
    private val profile = ProfileId("profile-a")
    private val book = LibraryItemId("book-a")
    private val baseline = AcknowledgedPause(book, 1.hours, generation = 7)

    @Test
    fun `material realtime fast forward is adopted`() {
        val decision = ResumeFreshnessPolicy.realtime(
            loadedProfile = profile,
            loadedBook = book,
            loadedSessionId = "bookwave-session",
            baseline = baseline,
            candidate = candidate(position = 1.hours + 3.minutes),
        )

        val adopted = assertIs<ResumeFreshnessDecision.Adopt>(decision)
        assertEquals(1.hours + 3.minutes, adopted.position)
        assertEquals(FreshnessEvidenceSource.Realtime, adopted.source)
    }

    @Test
    fun `material realtime rewind is adopted rather than max position`() {
        val decision = ResumeFreshnessPolicy.realtime(
            loadedProfile = profile,
            loadedBook = book,
            loadedSessionId = "bookwave-session",
            baseline = baseline,
            candidate = candidate(position = 30.minutes),
        )

        assertEquals(30.minutes, assertIs<ResumeFreshnessDecision.Adopt>(decision).position)
    }

    @Test
    fun `less than two minutes is treated as ordinary drift`() {
        val decision = ResumeFreshnessPolicy.realtime(
            loadedProfile = profile,
            loadedBook = book,
            loadedSessionId = "bookwave-session",
            baseline = baseline,
            candidate = candidate(position = 1.hours + 1.minutes),
        )

        assertIs<ResumeFreshnessDecision.Current>(decision)
    }

    @Test
    fun `own playback session echo never substitutes for a freshness check`() {
        val decision = ResumeFreshnessPolicy.realtime(
            loadedProfile = profile,
            loadedBook = book,
            loadedSessionId = "remote-session",
            baseline = baseline,
            candidate = candidate(position = 1.hours + 10.minutes),
        )

        assertNull(decision)
    }

    @Test
    fun `candidate from an older baseline generation is stale`() {
        val decision = ResumeFreshnessPolicy.realtime(
            loadedProfile = profile,
            loadedBook = book,
            loadedSessionId = "bookwave-session",
            baseline = baseline,
            candidate = candidate(position = 1.hours + 10.minutes, generation = 6),
        )

        assertNull(decision)
    }

    @Test
    fun `candidate for another profile or book is ignored`() {
        val wrongProfile = candidate(position = 1.hours + 10.minutes, candidateProfile = ProfileId("profile-b"))
        val wrongBook = candidate(position = 1.hours + 10.minutes, candidateBook = LibraryItemId("book-b"))

        assertNull(
            ResumeFreshnessPolicy.realtime(profile, book, "bookwave-session", baseline, wrongProfile),
        )
        assertNull(
            ResumeFreshnessPolicy.realtime(profile, book, "bookwave-session", baseline, wrongBook),
        )
    }

    @Test
    fun `material REST fast forward uses the same adoption threshold`() {
        val decision = ResumeFreshnessPolicy.rest(
            baseline = baseline,
            check = ExternalSessionCheck.Ahead(1.hours + 3.minutes),
        )

        val adopted = assertIs<ResumeFreshnessDecision.Adopt>(decision)
        assertEquals(1.hours + 3.minutes, adopted.position)
        assertEquals(FreshnessEvidenceSource.Rest, adopted.source)
    }

    @Test
    fun `material REST rewind is adopted rather than max position`() {
        val decision = ResumeFreshnessPolicy.rest(
            baseline = baseline,
            check = ExternalSessionCheck.Ahead(30.minutes),
        )

        val adopted = assertIs<ResumeFreshnessDecision.Adopt>(decision)
        assertEquals(30.minutes, adopted.position)
        assertEquals(FreshnessEvidenceSource.Rest, adopted.source)
    }

    @Test
    fun `exactly two minutes of REST drift stays local`() {
        val decision = ResumeFreshnessPolicy.rest(
            baseline = baseline,
            check = ExternalSessionCheck.Ahead(1.hours + 2.minutes),
        )

        val current = assertIs<ResumeFreshnessDecision.Current>(decision)
        assertEquals(FreshnessEvidenceSource.Rest, current.source)
    }

    @Test
    fun `unavailable REST check resumes locally but remains unverified`() {
        val decision = ResumeFreshnessPolicy.rest(
            baseline = baseline,
            check = ExternalSessionCheck.Unavailable,
        )

        val current = assertIs<ResumeFreshnessDecision.Current>(decision)
        assertEquals(FreshnessEvidenceSource.LocalUnverified, current.source)
    }

    private fun candidate(
        position: kotlin.time.Duration,
        generation: Long = baseline.generation,
        candidateProfile: ProfileId = profile,
        candidateBook: LibraryItemId = book,
    ) = RealtimeResumeCandidate(
        evidence = RealtimeProgressEvidence(
            profileId = candidateProfile,
            progress = AccountProgress(
                bookId = candidateBook,
                position = position,
                duration = 20.hours,
                isFinished = false,
                updatedAt = Instant.parse("2026-09-06T06:00:00Z"),
            ),
            sessionId = "remote-session",
        ),
        baselineGeneration = generation,
    )
}
