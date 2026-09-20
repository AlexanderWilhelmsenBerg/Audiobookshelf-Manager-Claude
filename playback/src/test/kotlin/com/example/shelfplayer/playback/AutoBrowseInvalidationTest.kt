package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.library.Book
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #10 + PD-001 — pure proof of BookWave's published browse shape. */
class AutoBrowseInvalidationTest {

    @Test
    fun `root membership and order changes invalidate root`() {
        val old = snapshot(mapOf(AutoLibrary.ROOT to listOf(
            AutoLibrary.TAB_CONTINUE, AutoLibrary.TAB_SERIES, AutoLibrary.TAB_AUTHORS, AutoLibrary.TAB_LIBRARY,
        )))
        val pd001 = snapshot(mapOf(AutoLibrary.ROOT to listOf(
            AutoLibrary.TAB_CONTINUE, AutoLibrary.TAB_SERIES, AutoLibrary.TAB_AUTHORS, AutoLibrary.TAB_PROFILES,
        )))
        assertEquals(setOf(AutoLibrary.ROOT), changedParents(old, pd001))
    }

    @Test
    fun `Series reorder with identical membership invalidates Series`() {
        assertEquals(
            setOf(AutoLibrary.TAB_SERIES),
            changedParents(
                snapshot(mapOf(AutoLibrary.TAB_SERIES to listOf("series-a", "series-b"))),
                snapshot(mapOf(AutoLibrary.TAB_SERIES to listOf("series-b", "series-a"))),
            ),
        )
    }

    @Test
    fun `same-count different Series and Author membership invalidates`() {
        assertEquals(
            setOf(AutoLibrary.TAB_SERIES),
            changedParents(
                snapshot(mapOf(AutoLibrary.TAB_SERIES to listOf("series-a"))),
                snapshot(mapOf(AutoLibrary.TAB_SERIES to listOf("series-b"))),
            ),
        )
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
    fun `same-count profile presentation change invalidates Profiles`() {
        assertEquals(
            setOf(AutoLibrary.TAB_PROFILES),
            changedParents(
                snapshot(mapOf(AutoLibrary.TAB_PROFILES to listOf("profile-a:old"))),
                snapshot(mapOf(AutoLibrary.TAB_PROFILES to listOf("profile-a:new"))),
            ),
        )
    }

    @Test
    fun `data mutation outside exposed shape causes no invalidation`() {
        val children = mapOf(AutoLibrary.TAB_SERIES to listOf("series-a"))
        val before = snapshot(children, accessibleBookIds = setOf(LibraryItemId("book-a")))
        val after = snapshot(children, accessibleBookIds = setOf(LibraryItemId("book-a"), LibraryItemId("not-exposed")))
        assertTrue(plan(before, after).notifications.isEmpty())
    }

    @Test
    fun `one candidate sweep uses one complete accessible-library subscription`() = runTest {
        var completeReads = 0
        val source = AutoBrowseSnapshotSource(
            activeProfiles = flowOf(ProfileId("profile-a")),
            savedProfiles = flowOf(emptyList<Profile>()),
            savedServers = flowOf(emptyList<Server>()),
            accessibleBooks = {
                completeReads += 1
                flowOf(emptyList<Book>())
            },
            build = { scope, _, _, _ ->
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
    fun `same active profile id does not create a new generation`() = runTest {
        val scopes = mutableListOf<BrowseProfileScope>()
        val source = AutoBrowseSnapshotSource(
            activeProfiles = flowOf(ProfileId("profile-a"), ProfileId("profile-a")),
            savedProfiles = flowOf(emptyList<Profile>()),
            savedServers = flowOf(emptyList<Server>()),
            accessibleBooks = { flowOf(emptyList()) },
            build = { scope, _, _, _ ->
                scopes += scope
                snapshot(emptyMap(), profileId = scope.profileId?.value, generation = scope.generation)
            },
        )
        source.snapshots().first()
        assertEquals(1, scopes.size)
    }

    @Test
    fun `profile boundary evicts PD-001 parents retired parents and emitted dynamic nodes`() {
        val currentParents = setOf(
            AutoLibrary.ROOT, AutoLibrary.RECENT_ROOT, AutoLibrary.TAB_CONTINUE,
            AutoLibrary.TAB_SERIES, AutoLibrary.TAB_AUTHORS, AutoLibrary.TAB_PROFILES,
        )
        val retired = setOf(
            AutoLibrary.TAB_LIBRARY, AutoLibrary.TAB_CHAPTERS, AutoLibrary.TAB_HISTORY,
            AutoLibrary.TAB_DOWNLOADS, AutoLibrary.TAB_RECENT, AutoLibrary.TAB_DISCOVER,
            AutoLibrary.TAB_AGAIN, AutoLibrary.TAB_OUTPUT,
        )
        val scoped = currentParents + retired
        val emitted = setOf(
            "${AutoLibrary.SERIES_PREFIX}old-series",
            "${AutoLibrary.AUTHOR_PREFIX}old-author",
        )
        val before = snapshot(
            children = scoped.associateWith { listOf("a") },
            profileId = "profile-a",
            generation = 1,
            profileScopedParents = scoped,
            deferredProfileCounts = setOf(AutoLibrary.RECENT_ROOT),
        )
        val after = snapshot(
            children = currentParents.associateWith { listOf("b") },
            profileId = "profile-b",
            generation = 2,
            profileScopedParents = scoped,
            deferredProfileCounts = setOf(AutoLibrary.RECENT_ROOT),
        )
        val boundary = plan(before, after, emitted)
        assertTrue(boundary.profileBoundary)
        assertEquals(scoped + emitted, boundary.notifications.mapTo(linkedSetOf()) { it.parentId })
        assertNull(boundary.notifications.first { it.parentId == AutoLibrary.RECENT_ROOT }.childCount)
        retired.forEach { parent ->
            assertEquals(0, boundary.notifications.first { it.parentId == parent }.childCount)
        }
    }

    @Test
    fun `old emitted dynamic parent is evicted when absent in new profile`() {
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
    fun `ordinary dynamic parent refreshes only after it was emitted`() {
        val dynamic = "${AutoLibrary.AUTHOR_PREFIX}author-a"
        val before = snapshot(mapOf(dynamic to listOf("book-a")), ordinaryParents = emptySet())
        val after = snapshot(mapOf(dynamic to listOf("book-b")), ordinaryParents = emptySet())
        assertTrue(plan(before, after).notifications.isEmpty())
        assertEquals(
            setOf(dynamic),
            plan(before, after, emitted = setOf(dynamic)).notifications.mapTo(linkedSetOf()) { it.parentId },
        )
    }

    @Test
    fun `PD-001 adapter exposes exactly four root destinations even with no books`() {
        val current = AutoBrowseSnapshotBuilder.build(
            scope = BrowseProfileScope(ProfileId("profile-a"), generation = 1),
            books = emptyList(),
            profiles = emptyList(),
            servers = emptyList(),
        )
        assertEquals(
            listOf(
                AutoLibrary.TAB_CONTINUE,
                AutoLibrary.TAB_SERIES,
                AutoLibrary.TAB_AUTHORS,
                AutoLibrary.TAB_PROFILES,
            ),
            current.childrenByParent[AutoLibrary.ROOT],
        )
        assertFalse(current.childrenByParent.containsKey(AutoLibrary.TAB_LIBRARY))
        assertFalse(current.childrenByParent.containsKey(AutoLibrary.TAB_HISTORY))
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
}
