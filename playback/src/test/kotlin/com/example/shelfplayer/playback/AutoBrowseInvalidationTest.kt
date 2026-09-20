package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.Book
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #10 — pure proof of BookWave's browse-shape publication contract.
 *
 * These tests intentionally stop at the Media3 notification plan. Whether a DHU or physical head unit redraws
 * after that notification is a separate platform acceptance boundary.
 */
class AutoBrowseInvalidationTest {

    @Test
    fun `empty to non-empty and back invalidates root`() {
        val empty = snapshot(mapOf(AutoLibrary.ROOT to listOf(AutoLibrary.NOTICE_EMPTY)))
        val populated = snapshot(
            mapOf(
                AutoLibrary.ROOT to listOf(
                    AutoLibrary.TAB_CONTINUE,
                    AutoLibrary.TAB_SERIES,
                    AutoLibrary.TAB_AUTHORS,
                    AutoLibrary.TAB_LIBRARY,
                ),
            ),
        )

        assertEquals(setOf(AutoLibrary.ROOT), changedParents(empty, populated))
        assertEquals(setOf(AutoLibrary.ROOT), changedParents(populated, empty))
    }

    @Test
    fun `first and last optional destination invalidate the library parent and destination`() {
        val withoutDownload = mapOf(
            AutoLibrary.TAB_LIBRARY to listOf(
                AutoLibrary.TAB_CHAPTERS,
                AutoLibrary.TAB_HISTORY,
                AutoLibrary.TAB_OUTPUT,
            ),
            AutoLibrary.TAB_DOWNLOADS to emptyList(),
        )
        val withDownload = mapOf(
            AutoLibrary.TAB_LIBRARY to listOf(
                AutoLibrary.TAB_CHAPTERS,
                AutoLibrary.TAB_HISTORY,
                AutoLibrary.TAB_DOWNLOADS,
                AutoLibrary.TAB_OUTPUT,
            ),
            AutoLibrary.TAB_DOWNLOADS to listOf("book-a"),
        )
        val affected = setOf(AutoLibrary.TAB_LIBRARY, AutoLibrary.TAB_DOWNLOADS)

        assertEquals(affected, changedParents(snapshot(withoutDownload), snapshot(withDownload)))
        assertEquals(affected, changedParents(snapshot(withDownload), snapshot(withoutDownload)))
    }

    @Test
    fun `series addition and removal invalidate Series`() {
        val one = snapshot(mapOf(AutoLibrary.TAB_SERIES to listOf("series-a")))
        val two = snapshot(mapOf(AutoLibrary.TAB_SERIES to listOf("series-a", "series-b")))

        assertEquals(setOf(AutoLibrary.TAB_SERIES), changedParents(one, two))
        assertEquals(setOf(AutoLibrary.TAB_SERIES), changedParents(two, one))
    }

    @Test
    fun `author addition and removal invalidate Authors`() {
        val one = snapshot(mapOf(AutoLibrary.TAB_AUTHORS to listOf("author-a")))
        val two = snapshot(mapOf(AutoLibrary.TAB_AUTHORS to listOf("author-a", "author-b")))

        assertEquals(setOf(AutoLibrary.TAB_AUTHORS), changedParents(one, two))
        assertEquals(setOf(AutoLibrary.TAB_AUTHORS), changedParents(two, one))
    }

    @Test
    fun `same-count different Series membership invalidates`() {
        assertEquals(
            setOf(AutoLibrary.TAB_SERIES),
            changedParents(
                snapshot(mapOf(AutoLibrary.TAB_SERIES to listOf("series-a"))),
                snapshot(mapOf(AutoLibrary.TAB_SERIES to listOf("series-b"))),
            ),
        )
    }

    @Test
    fun `same-count different Author membership invalidates`() {
        assertEquals(
            setOf(AutoLibrary.TAB_AUTHORS),
            changedParents(
                snapshot(mapOf(AutoLibrary.TAB_AUTHORS to listOf("author-a"))),
                snapshot(mapOf(AutoLibrary.TAB_AUTHORS to listOf("author-b"))),
            ),
        )
    }

    @Test
    fun `Continue meaningful membership or ordering change invalidates Continue`() {
        assertEquals(
            setOf(AutoLibrary.TAB_CONTINUE),
            changedParents(
                snapshot(mapOf(AutoLibrary.TAB_CONTINUE to listOf("book-a", "book-b"))),
                snapshot(mapOf(AutoLibrary.TAB_CONTINUE to listOf("book-b", "book-a"))),
            ),
        )
    }

    @Test
    fun `data mutation that leaves exposed shape unchanged causes no invalidation`() {
        val children = mapOf(AutoLibrary.TAB_SERIES to listOf("series-a"))
        val before = snapshot(
            children = children,
            accessibleBookIds = setOf(LibraryItemId("book-a")),
        )
        val after = snapshot(
            children = children,
            accessibleBookIds = setOf(LibraryItemId("book-a"), LibraryItemId("book-not-exposed")),
        )

        assertTrue(plan(before, after).notifications.isEmpty())
    }

    @Test
    fun `one candidate sweep uses one complete accessible-library subscription for many parents`() = runTest {
        var completeReads = 0
        val source = AutoBrowseSnapshotSource(
            activeProfiles = flowOf(ProfileId("profile-a")),
            accessibleBooks = {
                completeReads += 1
                flowOf(emptyList<Book>())
            },
            build = { scope, _ ->
                val children = (1..200).associate { index -> "parent-$index" to listOf("child-$index") }
                AutoBrowseSnapshot(
                    scope = scope,
                    childrenByParent = children,
                    ordinaryParents = children.keys,
                    profileScopedParents = children.keys,
                    deferredProfileCounts = emptySet(),
                    accessibleBookIds = emptySet(),
                )
            },
        )

        val result = source.snapshots().first()

        assertEquals(1, completeReads)
        assertEquals(200, result.childrenByParent.size)
    }

    @Test
    fun `same active profile id does not create a new profile generation`() = runTest {
        val scopes = mutableListOf<BrowseProfileScope>()
        val source = AutoBrowseSnapshotSource(
            activeProfiles = flowOf(
                ProfileId("profile-a"),
                ProfileId("profile-a"),
            ),
            accessibleBooks = { flowOf(emptyList()) },
            build = { scope, _ ->
                scopes += scope
                snapshot(
                    children = emptyMap(),
                    profileId = scope.profileId?.value,
                    generation = scope.generation,
                )
            },
        )

        source.snapshots().first()

        assertEquals(1, scopes.size)
    }

    @Test
    fun `profile A to B invalidates every profile-scoped parent and emitted dynamic parent`() {
        val staticParents = setOf(
            AutoLibrary.ROOT,
            AutoLibrary.RECENT_ROOT,
            AutoLibrary.TAB_CONTINUE,
            AutoLibrary.TAB_CHAPTERS,
            AutoLibrary.TAB_HISTORY,
            AutoLibrary.TAB_LIBRARY,
            AutoLibrary.TAB_SERIES,
            AutoLibrary.TAB_AUTHORS,
            AutoLibrary.TAB_DOWNLOADS,
            AutoLibrary.TAB_RECENT,
            AutoLibrary.TAB_DISCOVER,
            AutoLibrary.TAB_AGAIN,
        )
        val emitted = setOf(
            "${AutoLibrary.SERIES_PREFIX}old-series",
            "${AutoLibrary.AUTHOR_PREFIX}old-author",
        )
        val before = snapshot(
            children = staticParents.associateWith { listOf("a") },
            profileId = "profile-a",
            generation = 1,
            profileScopedParents = staticParents,
            deferredProfileCounts = deferredParents,
        )
        val after = snapshot(
            children = staticParents.associateWith { listOf("b") },
            profileId = "profile-b",
            generation = 2,
            profileScopedParents = staticParents,
            deferredProfileCounts = deferredParents,
        )

        val boundary = plan(before, after, emitted)

        assertTrue(boundary.profileBoundary)
        assertEquals(staticParents + emitted, boundary.notifications.mapTo(linkedSetOf()) { it.parentId })
        deferredParents.forEach { parentId ->
            assertNull(boundary.notifications.first { it.parentId == parentId }.childCount)
        }
    }

    @Test
    fun `equal counts across profile boundary cannot suppress invalidation`() {
        val parent = AutoLibrary.TAB_SERIES
        val boundary = plan(
            before = snapshot(
                children = mapOf(parent to listOf("series-a")),
                profileId = "profile-a",
                generation = 1,
                profileScopedParents = setOf(parent),
            ),
            after = snapshot(
                children = mapOf(parent to listOf("series-b")),
                profileId = "profile-b",
                generation = 2,
                profileScopedParents = setOf(parent),
            ),
        )

        assertTrue(boundary.profileBoundary)
        assertEquals(listOf(BrowseInvalidation(parent, 1)), boundary.notifications)
    }

    @Test
    fun `old emitted dynamic parent is evicted even when absent from the new snapshot`() {
        val oldParent = "${AutoLibrary.SERIES_PREFIX}old-series"
        val boundary = plan(
            before = snapshot(
                children = mapOf(oldParent to listOf("book-a")),
                profileId = "profile-a",
                generation = 1,
                profileScopedParents = setOf(AutoLibrary.TAB_SERIES),
            ),
            after = snapshot(
                children = mapOf(AutoLibrary.TAB_SERIES to emptyList()),
                profileId = "profile-b",
                generation = 2,
                profileScopedParents = setOf(AutoLibrary.TAB_SERIES),
            ),
            emitted = setOf(oldParent),
        )

        assertEquals(0, boundary.notifications.first { it.parentId == oldParent }.childCount)
    }

    @Test
    fun `ordinary dynamic parent refreshes only after it has actually been emitted`() {
        val dynamic = "${AutoLibrary.AUTHOR_PREFIX}author-a"
        val before = snapshot(
            children = mapOf(dynamic to listOf("book-a")),
            ordinaryParents = emptySet(),
        )
        val after = snapshot(
            children = mapOf(dynamic to listOf("book-b")),
            ordinaryParents = emptySet(),
        )

        assertTrue(plan(before, after).notifications.isEmpty())
        assertEquals(
            setOf(dynamic),
            plan(before, after, emitted = setOf(dynamic)).notifications.mapTo(linkedSetOf()) { it.parentId },
        )
    }

    @Test
    fun `current adapter keeps issue 65 hierarchy out of this change`() {
        val current = CurrentAutoBrowseSnapshotBuilder.build(
            BrowseProfileScope(ProfileId("profile-a"), generation = 1),
            books = emptyList(),
        )

        assertEquals(listOf(AutoLibrary.NOTICE_EMPTY), current.childrenByParent[AutoLibrary.ROOT])
        assertEquals(
            listOf(
                AutoLibrary.TAB_CHAPTERS,
                AutoLibrary.TAB_HISTORY,
                AutoLibrary.TAB_OUTPUT,
            ),
            current.childrenByParent[AutoLibrary.TAB_LIBRARY],
        )
        assertFalse(current.childrenByParent.values.flatten().any { it == "tab/profiles" })
    }

    private fun changedParents(before: AutoBrowseSnapshot, after: AutoBrowseSnapshot): Set<String> =
        plan(before, after).notifications.mapTo(linkedSetOf()) { it.parentId }

    private fun plan(
        before: AutoBrowseSnapshot,
        after: AutoBrowseSnapshot,
        emitted: Set<String> = emptySet(),
    ): BrowseInvalidationPlan {
        val tracker = AutoBrowseInvalidationTracker()
        assertEquals(BrowseInvalidationPlan.None, tracker.next(before, emitted))
        return tracker.next(after, emitted)
    }

    private fun snapshot(
        children: Map<String, List<String>>,
        profileId: String? = "profile-a",
        generation: Long = 1,
        ordinaryParents: Set<String> = children.keys,
        profileScopedParents: Set<String> = ordinaryParents,
        deferredProfileCounts: Set<String> = emptySet(),
        accessibleBookIds: Set<LibraryItemId> = emptySet(),
    ): AutoBrowseSnapshot = AutoBrowseSnapshot(
        scope = BrowseProfileScope(profileId?.let(::ProfileId), generation),
        childrenByParent = children,
        ordinaryParents = ordinaryParents,
        profileScopedParents = profileScopedParents,
        deferredProfileCounts = deferredProfileCounts,
        accessibleBookIds = accessibleBookIds,
    )

    private companion object {
        val deferredParents = setOf(
            AutoLibrary.RECENT_ROOT,
            AutoLibrary.TAB_CHAPTERS,
            AutoLibrary.TAB_HISTORY,
        )
    }
}
