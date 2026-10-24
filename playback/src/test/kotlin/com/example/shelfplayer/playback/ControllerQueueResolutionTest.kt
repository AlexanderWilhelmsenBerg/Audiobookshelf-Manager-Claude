package com.example.shelfplayer.playback

import androidx.media3.common.MediaItem
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.PlaybackSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** Exercises the production callback transaction, including its suspended durable-acceptance predicate. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ControllerQueueResolutionTest {
    @Test
    fun `cancellation during durable preparation revokes publication without a generation change`() = runTest {
        val fixture = Fixture()
        fixture.beforeAccept = { currentCoroutineContext().cancel() }
        val request = async { fixture.owner.resolve(fixture.owner.capture(), BOOK, null) }
        assertFailsWith<CancellationException> { request.await() }
        assertEquals(0, fixture.published)
    }

    @Test
    fun `accepted queue preserves server start and publishes once`() = runTest {
        val fixture = Fixture()
        val result = fixture.owner.resolve(fixture.owner.capture(), BOOK, null)
        assertEquals(42_000L, result?.startPositionMs)
        assertEquals(1, fixture.published)
        assertEquals(1, fixture.prepared)
    }

    @Test
    fun `a newer Stop during remote prepare never publishes the old session`() = runTest {
        val fixture = Fixture()
        val release = CompletableDeferred<Unit>()
        fixture.beforePrepare = { release.await() }
        val pending = async(UnconfinedTestDispatcher(testScheduler)) {
            fixture.owner.resolve(fixture.owner.capture(), BOOK, null)
        }
        fixture.commandGeneration++
        release.complete(Unit)
        assertFailsWith<CancellationException> { pending.await() }
        assertEquals(0, fixture.published)
    }

    @Test
    fun `profile A to B to A invalidates suspended remote prepare`() = runTest {
        val fixture = Fixture()
        fixture.beforePrepare = { fixture.profileGeneration += 2 }
        assertFailsWith<CancellationException> { fixture.owner.resolve(fixture.owner.capture(), BOOK, null) }
        assertEquals(0, fixture.published)
    }

    @Test
    fun `replacement player invalidates suspended remote prepare`() = runTest {
        val fixture = Fixture()
        fixture.beforePrepare = { fixture.serviceOwner = Any() }
        assertFailsWith<CancellationException> { fixture.owner.resolve(fixture.owner.capture(), BOOK, null) }
        assertEquals(0, fixture.published)
    }

    @Test
    fun `service destruction invalidates suspended remote prepare`() = runTest {
        val fixture = Fixture()
        fixture.beforePrepare = { fixture.serviceOwner = null }
        assertFailsWith<CancellationException> { fixture.owner.resolve(fixture.owner.capture(), BOOK, null) }
        assertEquals(0, fixture.published)
    }

    @Test
    fun `wrong session profile is rejected before live session acceptance`() = runTest {
        val fixture = Fixture()
        fixture.sessionProfile = ProfileId("other")
        assertFailsWith<CancellationException> { fixture.owner.resolve(fixture.owner.capture(), BOOK, null) }
        assertEquals(0, fixture.accepted)
    }

    @Test
    fun `newer intent during durable session preparation revokes commit`() = runTest {
        val fixture = Fixture()
        fixture.beforeAccept = { fixture.commandGeneration++ }
        assertFailsWith<CancellationException> { fixture.owner.resolve(fixture.owner.capture(), BOOK, null) }
        assertEquals(1, fixture.accepted)
        assertEquals(0, fixture.published)
    }

    @Test
    fun `lock during profile lookup revokes commit even if identity stays unchanged`() = runTest {
        val fixture = Fixture()
        fixture.beforeAccept = { fixture.locked = true }
        assertFailsWith<CancellationException> { fixture.owner.resolve(fixture.owner.capture(), BOOK, null) }
        assertEquals(0, fixture.published)
    }

    @Test
    fun `ordinary unresolved request has no session side effects`() = runTest {
        val fixture = Fixture()
        fixture.missing = true
        assertNull(fixture.owner.resolve(fixture.owner.capture(), BOOK, null))
        assertEquals(0, fixture.accepted)
        assertEquals(0, fixture.published)
    }

    @Test
    fun `failed old network request is cancelled rather than succeeding with empty queue`() = runTest {
        val fixture = Fixture()
        fixture.missing = true
        fixture.beforePrepare = { fixture.commandGeneration++ }
        assertFailsWith<CancellationException> { fixture.owner.resolve(fixture.owner.capture(), BOOK, null) }
        assertEquals(0, fixture.published)
    }

    @Test
    fun `batch preparation has no live effects until all candidates finish and first is accepted`() = runTest {
        val fixture = Fixture()
        val claim = fixture.owner.capture()
        val first = fixture.owner.prepareForRequest(claim, BOOK, null)!!
        fixture.owner.prepareForRequest(claim, BOOK, null)
        assertEquals(0, fixture.published)
        fixture.owner.acceptPrepared(claim, first)
        assertEquals(1, fixture.published)
        assertEquals(1, fixture.accepted)
    }

    @Test
    fun `batch invalidated while preparing later candidate never publishes earlier book`() = runTest {
        val fixture = Fixture()
        val claim = fixture.owner.capture()
        fixture.owner.prepareForRequest(claim, BOOK, null)
        fixture.beforePrepare = { fixture.commandGeneration++ }
        assertFailsWith<CancellationException> { fixture.owner.prepareForRequest(claim, BOOK, null) }
        assertEquals(0, fixture.published)
        assertEquals(0, fixture.accepted)
    }

    private class Fixture {
        var commandGeneration = 0L
        var profileGeneration = 0L
        var serviceOwner: Any? = Any()
        var sessionProfile = PROFILE
        var locked = false
        var missing = false
        var prepared = 0
        var accepted = 0
        var published = 0
        var beforePrepare: suspend () -> Unit = {}
        var beforeAccept: suspend () -> Unit = {}
        val owner = ControllerQueueResolution(
            commandGeneration = { commandGeneration },
            profileGeneration = { profileGeneration },
            serviceOwner = { serviceOwner },
            activeProfileId = { PROFILE },
            isProfileLocked = { locked },
            prepare = { _, _ ->
                prepared++
                beforePrepare()
                if (missing) {
                    null
                } else {
                    CarPostConnectRestorer.Prepared(
                        session(sessionProfile),
                        MediaItems.Queue(MediaItem.Builder().setMediaId("resolved").build(), 42_000L),
                    )
                }
            },
            accept = { _, authorized, install ->
                accepted++
                beforeAccept()
                if (authorized()) {
                    published++
                    install()
                    true
                } else {
                    false
                }
            },
        )
    }

    private companion object {
        val PROFILE = ProfileId("first")
        val BOOK = LibraryItemId("book")
        fun session(profile: ProfileId) = PlaybackSession(
            id = "remote-test",
            profileId = profile,
            bookId = BOOK,
            title = "Test book",
            author = null,
            coverUrl = null,
            startAt = 42.seconds,
            duration = 1.hours,
            tracks = emptyList(),
            chapters = emptyList(),
        )
    }
}
