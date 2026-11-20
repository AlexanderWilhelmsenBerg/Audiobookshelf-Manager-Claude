package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.common.connectivity.NetworkMonitor
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.domain.FakeProfileRepository
import com.example.shelfplayer.domain.book
import com.example.shelfplayer.domain.library.SeriesOrder
import com.example.shelfplayer.domain.library.SeriesOrderMove
import com.example.shelfplayer.domain.library.SeriesOrderRow
import com.example.shelfplayer.domain.library.groupIntoSeries
import com.example.shelfplayer.domain.profile
import com.example.shelfplayer.domain.repository.MetadataSaveResult
import com.example.shelfplayer.domain.repository.SeriesOrderRepository
import com.example.shelfplayer.domain.repository.SeriesSequenceRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaveSeriesOrderUseCaseTest {
    private val active = profile().copy(canUpdate = true)
    private val profileRepository = FakeProfileRepository(active)
    private val series = groupIntoSeries(
        listOf(book(id = "first", sequence = "1"), book(id = "second", sequence = "2")),
    ).single()
    private val original = SeriesOrder.from(series, emptySet())
    private val owner = SeriesOrderOwner(active.id, 0)
    private val store = RecordingGaps()
    private val fresh = AppResult.Success(MetadataSaveResult(book = null, isLocalCopyStale = false))

    @Test
    fun `a missing book can be saved offline without a metadata update`() = runTest {
        val useCase = useCase(online = false) { _, _, _, _ -> error("Cannot write series metadata offline") }
        val result = useCase(owner, series.series.id, original.addMissing(4))

        assertNull(result.error)
        assertEquals(setOf(4), store.saved)
    }

    @Test
    fun `the result records partial writes and the draft can be retried`() = runTest {
        val applied = mutableSetOf<LibraryItemId>()
        var connected = false
        val useCase = useCase { profile, id, change, _ ->
            assertEquals(active.id to series.series.id, profile to id)
            if (!connected && change.bookId.value == "first") {
                AppResult.Failure(AppError.Timeout())
            } else {
                applied += change.bookId
                fresh
            }
        }
        val reorder = original.move(
            SeriesOrderRow(original.slots[1].key),
            SeriesOrderRow(original.slots[0].key),
            SeriesOrderMove.Reorder,
        ).addMissing(3)

        val partial = useCase(owner, series.series.id, reorder)
        assertEquals(1 to 2, partial.applied to partial.total)
        assertIs<AppError.Timeout>(partial.error)
        assertTrue(store.saved.isEmpty())

        connected = true
        assertEquals(listOf("first"), partial.order.changes.map { it.bookId.value })
        val retry = useCase(owner, series.series.id, partial.order)
        assertNull(retry.error)
        assertEquals(2, applied.size)
        assertEquals(setOf(3), store.saved)
    }

    @Test
    fun `a confirmed server save with a failed readback is reported separately from unsaved changes`() = runTest {
        val useCase = useCase { _, _, change, _ ->
            AppResult.Success(MetadataSaveResult(book = null, isLocalCopyStale = change.bookId.value == "second"))
        }

        val result = useCase(owner, series.series.id, swapFirstTwo(original))

        assertNull(result.error)
        assertEquals(2, result.applied)
        assertEquals(setOf(LibraryItemId("second")), result.unrefreshedBooks)
        assertTrue(result.order.changes.isEmpty())
    }

    @Test
    fun `undo after an interrupted swap restores both confirmed and uncertain server writes`() = runTest {
        val server = mutableMapOf("first" to "1", "second" to "2")
        var loseResponse = true
        val useCase = useCase { _, _, change, _ ->
            val id = change.bookId.value
            val current = server.getValue(id)
            if (current == change.after) return@useCase fresh
            if (current != change.before && current !in change.possibleBefore) {
                return@useCase AppResult.Failure(AppError.Conflict("The sequence changed."))
            }
            server[id] = change.after
            if (loseResponse && id == "first") AppResult.Failure(AppError.Timeout()) else fresh
        }
        val rearranged = swapFirstTwo(original)
        val interrupted = useCase(owner, series.series.id, rearranged)
        assertEquals(1, interrupted.applied)
        assertIs<AppError.Timeout>(interrupted.error)
        assertEquals(mapOf("first" to "2", "second" to "1"), server)

        loseResponse = false
        val undone = swapFirstTwo(interrupted.order)
        assertEquals(2, undone.changes.size)
        val complete = useCase(owner, series.series.id, undone)
        assertNull(complete.error)
        assertEquals(mapOf("first" to "1", "second" to "2"), server)
        assertTrue(complete.order.changes.isEmpty())
    }

    private fun swapFirstTwo(order: SeriesOrder): SeriesOrder = order.move(
        SeriesOrderRow(order.slots[1].key),
        SeriesOrderRow(order.slots[0].key),
        SeriesOrderMove.Reorder,
    )

    @Test
    fun `an old profile cannot save after selection changes`() = runTest {
        profileRepository.setActiveProfile(ProfileId("different"))
        val useCase = useCase { _, _, _, _ -> error("Cannot edit on an inactive profile") }
        val failure = useCase(owner, series.series.id, original.addMissing(3))

        assertIs<AppError.Conflict>(failure.error)
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `a profile switch during fresh metadata lookup retires the pending write`() = runTest {
        val useCase = useCase { _, _, _, beforeWrite ->
            profileRepository.setActiveProfile(ProfileId("different"))
            AppResult.Failure(assertIs<AppError.Conflict>(beforeWrite()))
        }
        val moved = original.move(
            SeriesOrderRow(original.slots[1].key),
            SeriesOrderRow(original.slots[0].key),
            SeriesOrderMove.Reorder,
        )
        val result = useCase(owner, series.series.id, moved)

        assertIs<AppError.Conflict>(result.error)
        assertEquals(0, result.applied)
        assertTrue(store.saved.isEmpty())
    }

    private fun useCase(online: Boolean = true, sequences: SeriesSequenceRepository): SaveSeriesOrderUseCase {
        val network = object : NetworkMonitor {
            override val isOnline = flowOf(online)
            override val isUnmetered = flowOf(online)
        }
        return SaveSeriesOrderUseCase(profileRepository, network, store, sequences)
    }

    private class RecordingGaps : SeriesOrderRepository {
        var saved: Set<Int> = emptySet()

        override fun observeMissingBooks(profileId: ProfileId, seriesId: SeriesId) = flowOf(saved)

        override suspend fun saveMissingBooks(
            profileId: ProfileId,
            seriesId: SeriesId,
            numbers: Set<Int>,
        ): AppResult<Unit> {
            saved = numbers
            return AppResult.Success(Unit)
        }
    }
}
