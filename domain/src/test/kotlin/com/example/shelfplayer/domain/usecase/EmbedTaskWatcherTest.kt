package com.example.shelfplayer.domain.usecase

import app.cash.turbine.test
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.realtime.RealtimeEvent
import com.example.shelfplayer.core.model.realtime.RealtimeStatus
import com.example.shelfplayer.core.model.realtime.ServerTask
import com.example.shelfplayer.domain.realtime.RealtimeUpdates
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/**
 * PRODUCT_SPEC MGR-007 — the filter, which is the whole of this class and the easy thing to get wrong.
 *
 * A server pushes every task it runs down the same socket with the same shape: a library scan somebody
 * started in the web interface, an m4b encode, and this app's own embed. Matching on the action alone would
 * report another book's failure against the one on screen; matching on the item alone would report a scan's
 * completion as an embed's.
 */
class EmbedTaskWatcherTest {

    private val profileId = ProfileId("profile-1")
    private val bookId = LibraryItemId("li_9x2k")

    @Test
    fun `a finished embed for this book is reported`() = runTest {
        val watcher = EmbedTaskWatcher(updates(task(isFinished = true)))

        watcher.outcomes(profileId, bookId).test {
            assertEquals(EmbedTaskState.Finished, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `a started embed reports that it is running`() = runTest {
        val watcher = EmbedTaskWatcher(updates(task(isFinished = false)))

        watcher.outcomes(profileId, bookId).test {
            assertEquals(EmbedTaskState.Running, awaitItem())
            awaitComplete()
        }
    }

    /** The failure carries whether the server explained itself, which is a different sentence on screen. */
    @Test
    fun `a failed embed reports whether the server gave a reason`() = runTest {
        val explained = EmbedTaskWatcher(updates(task(isFinished = true, isFailed = true, hasError = true)))
        explained.outcomes(profileId, bookId).test {
            assertEquals(EmbedTaskState.Failed(hasServerError = true), awaitItem())
            awaitComplete()
        }

        val silent = EmbedTaskWatcher(updates(task(isFinished = true, isFailed = true, hasError = false)))
        silent.outcomes(profileId, bookId).test {
            assertEquals(EmbedTaskState.Failed(hasServerError = false), awaitItem())
            awaitComplete()
        }
    }

    /** Another book's embed. The one that would have been reported against the book on screen. */
    @Test
    fun `an embed for a different item is ignored`() = runTest {
        val watcher = EmbedTaskWatcher(updates(task(itemId = "li_other", isFinished = true)))

        watcher.outcomes(profileId, bookId).test { awaitComplete() }
    }

    /** A library scan of the same item, which is MGR-004's operation and not this one. */
    @Test
    fun `another kind of task for the same item is ignored`() = runTest {
        val watcher = EmbedTaskWatcher(updates(task(action = "library-scan", isFinished = true)))

        watcher.outcomes(profileId, bookId).test { awaitComplete() }
    }

    /** The account event that shares this stream, and has nothing to do with tasks. */
    @Test
    fun `a non-task event is ignored`() = runTest {
        val watcher = EmbedTaskWatcher(
            object : RealtimeUpdates {
                override val status: StateFlow<RealtimeStatus> = MutableStateFlow(RealtimeStatus.Connected)
                override fun events(profileId: ProfileId): Flow<RealtimeEvent> = emptyList<RealtimeEvent>().asFlow()
            },
        )

        watcher.outcomes(profileId, bookId).test { awaitComplete() }
    }

    /** Exposed so the screen can tell "not finished yet" from "the answer can no longer arrive". */
    @Test
    fun `the connection status is passed through`() = runTest {
        val updates = updates()
        assertEquals(RealtimeStatus.Connected, EmbedTaskWatcher(updates).connection.value)
    }

    private fun task(
        action: String = ServerTask.EMBED_METADATA,
        itemId: String? = "li_9x2k",
        isFinished: Boolean = false,
        isFailed: Boolean = false,
        hasError: Boolean = false,
    ) = ServerTask(
        id = "task_7",
        action = action,
        libraryItemId = itemId,
        isFinished = isFinished,
        isFailed = isFailed,
        hasError = hasError,
    )

    private fun updates(vararg tasks: ServerTask) = object : RealtimeUpdates {
        override val status: StateFlow<RealtimeStatus> = MutableStateFlow(RealtimeStatus.Connected)

        override fun events(profileId: ProfileId): Flow<RealtimeEvent> = tasks.map(RealtimeEvent::TaskChanged).asFlow()
    }
}
