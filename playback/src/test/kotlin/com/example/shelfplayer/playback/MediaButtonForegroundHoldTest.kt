package com.example.shelfplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalCoroutinesApi::class)
class MediaButtonForegroundHoldTest {
    @Test
    fun `a timed out receiver start cancels pending resumption and abandons that start once`() = runTest {
        val finished = mutableListOf<Int>()
        val hold = MediaButtonForegroundHold(backgroundScope, MoreExecutors.directExecutor(), finished::add)
        val resume = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        hold.start(7)
        hold.watch(resume)

        advanceTimeBy(30_000)
        runCurrent()

        assertTrue(resume.isCancelled)
        assertNull(hold.current)
        assertEquals(listOf(7), finished)
    }

    @Test
    fun `a replaced attempt cannot drop foreground for a newer play on the same start`() = runTest {
        val finished = mutableListOf<Int>()
        val hold = MediaButtonForegroundHold(backgroundScope, MoreExecutors.directExecutor(), finished::add)
        val first = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        val second = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        hold.start(7)
        hold.watch(first)
        val firstAttempt = requireNotNull(hold.current)
        advanceTimeBy(25_000)
        hold.watch(second)
        hold.handOff(firstAttempt)
        advanceTimeBy(6_000)

        assertTrue(first.isCancelled)
        assertFalse(second.isDone)
        assertTrue(finished.isEmpty())
        second.setException(IllegalStateException("fixture"))
        assertEquals(listOf(7), finished)
    }
}
