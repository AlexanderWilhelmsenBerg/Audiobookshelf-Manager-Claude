package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Author
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.LocalAvailability
import com.example.shelfplayer.core.model.library.MediaProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** #10 + PD-001 — pure proof of BookWave's published browse shape. */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoBrowseInvalidationTest {

    @Test
    fun `root membership and order changes invalidate root`() {
        val old = snapshot(
            mapOf(
                AutoLibrary.ROOT to listOf(
                    AutoLibrary.TAB_CONTINUE,
                    AutoLibrary.TAB_SERIES,
                    AutoLibrary.TAB_AUTHORS,
                    AutoLibrary.TAB_LIBRARY,
                ),
            ),
        )
        val pd001 = snapshot(
            mapOf(
                AutoLibrary.ROOT to listOf(
                    AutoLibrary.TAB_CONTINUE,
                    AutoLibrary.TAB_SERIES,
                    AutoLibrary.TAB_AUTHORS,
                    AutoLibrary.TAB_PROFILES,
                ),
            ),
        )
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
            rememberedBook = { flowOf(null) },
            build = { scope, _, _, _, _ ->
                val children = (1..200).associate { index -> "parent-$index" to listOf("child-$index") }
                AutoBrowseSnapshot(
                    scope = scope,
                    childrenByParent = children,
                    ordinaryParents = children.keys,
                    profileScopedParents = children.keys,
                    deferredProfileCounts = emptySet(),
                    accessibleBookIds = emptySet(),
                    resumableBookIds = emptySet(),
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
            rememberedBook = { flowOf(null) },
            build = { scope, _, _, _, _ ->
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
            AutoLibrary.ROOT,
            AutoLibrary.RECENT_ROOT,
            AutoLibrary.TAB_CONTINUE,
            AutoLibrary.TAB_SERIES,
            AutoLibrary.TAB_AUTHORS,
            AutoLibrary.TAB_PROFILES,
        )
        val retired = setOf(
            AutoLibrary.TAB_LIBRARY,
            AutoLibrary.TAB_CHAPTERS,
            AutoLibrary.TAB_HISTORY,
            AutoLibrary.TAB_DOWNLOADS,
            AutoLibrary.TAB_RECENT,
            AutoLibrary.TAB_DISCOVER,
            AutoLibrary.TAB_AGAIN,
            AutoLibrary.TAB_OUTPUT,
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
            rememberedId = null,
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

    @Test
    fun `remembered book change within a profile invalidates the resume root with its child count`() = runTest {
        val remembered = MutableStateFlow<LibraryItemId?>(null)
        val library = listOf(book("book-a"), book("book-b"))
        val source = sourceOf(library, remembered)
        val seen = mutableListOf<AutoBrowseSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.snapshots().collect { seen += it } }
        val tracker = AutoBrowseInvalidationTracker()
        assertEquals(BrowseInvalidationPlan.None, tracker.next(seen.last(), emptySet()))
        assertEquals(emptyList(), seen.last().childrenByParent[AutoLibrary.RECENT_ROOT])

        remembered.value = LibraryItemId("book-a")
        val remembering = tracker.next(seen.last(), emptySet())
        assertFalse(remembering.profileBoundary)
        assertEquals(listOf(BrowseInvalidation(AutoLibrary.RECENT_ROOT, 1)), remembering.notifications)

        remembered.value = LibraryItemId("book-b")
        val switching = tracker.next(seen.last(), emptySet())
        assertEquals(listOf(BrowseInvalidation(AutoLibrary.RECENT_ROOT, 1)), switching.notifications)
        assertEquals(listOf("book-b"), seen.last().childrenByParent[AutoLibrary.RECENT_ROOT])

        remembered.value = null
        val forgetting = tracker.next(seen.last(), emptySet())
        assertEquals(listOf(BrowseInvalidation(AutoLibrary.RECENT_ROOT, 0)), forgetting.notifications)
    }

    @Test
    fun `an unrelated change does not invalidate the resume root`() = runTest {
        val remembered = MutableStateFlow<LibraryItemId?>(LibraryItemId("book-a"))
        val source = sourceOf(listOf(book("book-a")), remembered)
        val seen = mutableListOf<AutoBrowseSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.snapshots().collect { seen += it } }
        val tracker = AutoBrowseInvalidationTracker()
        tracker.next(seen.last(), emptySet())
        val emissions = seen.size

        remembered.value = LibraryItemId("book-a")
        assertEquals(emissions, seen.size, "re-remembering the same book is not a change")

        val grown = AutoBrowseSnapshotBuilder.build(
            scope = seen.last().scope,
            books = listOf(book("book-a"), book("book-unplayed")),
            profiles = emptyList(),
            servers = emptyList(),
            rememberedId = LibraryItemId("book-a"),
        )
        assertTrue(tracker.next(grown, emptySet()).notifications.none { it.parentId == AutoLibrary.RECENT_ROOT })
    }

    @Test
    fun `the resume root follows the same candidate rule as the resume row`() {
        fun children(books: List<Book>, remembered: String?) = AutoBrowseSnapshotBuilder.build(
            scope = BrowseProfileScope(ProfileId("profile-a"), generation = 1),
            books = books,
            profiles = emptyList(),
            servers = emptyList(),
            rememberedId = remembered?.let(::LibraryItemId),
        ).childrenByParent[AutoLibrary.RECENT_ROOT]

        assertEquals(listOf("book-a"), children(listOf(book("book-a"), book("book-b")), "book-a"))
        assertEquals(emptyList(), children(listOf(book("book-a")), "missing"))
        assertEquals(emptyList(), children(listOf(book("book-a", finished = true)), "book-a"))
        assertEquals(
            listOf("book-b"),
            children(listOf(book("book-a"), book("book-b", progressed = true)), null),
            "without a remembered book the newest Continue book is the candidate",
        )
    }

    private fun sourceOf(books: List<Book>, remembered: Flow<LibraryItemId?>) = AutoBrowseSnapshotSource(
        activeProfiles = flowOf(ProfileId("profile-a")),
        savedProfiles = flowOf(emptyList<Profile>()),
        savedServers = flowOf(emptyList<Server>()),
        accessibleBooks = { flowOf(books) },
        rememberedBook = { remembered },
        build = AutoBrowseSnapshotBuilder::build,
    )

    private fun book(id: String, progressed: Boolean = false, finished: Boolean = false) = Book(
        serverId = SERVER,
        id = LibraryItemId(id),
        libraryId = LibraryId("lib"),
        title = id,
        subtitle = null,
        authors = listOf(Author(SERVER, AuthorId("author-1"), "Author")),
        narrators = emptyList(),
        seriesMemberships = emptyList(),
        duration = 4.hours,
        description = null,
        genres = emptyList(),
        tags = emptyList(),
        publishedYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        isExplicit = false,
        isAbridged = false,
        coverPath = null,
        trackCount = 1,
        sizeBytes = 0,
        remoteUpdatedAt = null,
        addedAt = Instant.ofEpochMilli(1_000),
        lastFetchedAt = Instant.ofEpochMilli(0),
        progress = if (progressed || finished) {
            MediaProgress(
                serverId = SERVER,
                profileId = ProfileId("profile-a"),
                bookId = LibraryItemId(id),
                position = 1.hours,
                duration = 4.hours,
                isFinished = finished,
                updatedAt = Instant.ofEpochMilli(2_000),
                hasUnsyncedChanges = false,
            )
        } else {
            null
        },
        localAvailability = LocalAvailability.NotDownloaded,
    )

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
        resumableBookIds: Set<LibraryItemId> = emptySet(),
    ): AutoBrowseSnapshot = AutoBrowseSnapshot(
        scope = BrowseProfileScope(profileId?.let(::ProfileId), generation),
        childrenByParent = children,
        ordinaryParents = ordinaryParents,
        profileScopedParents = profileScopedParents,
        deferredProfileCounts = deferredProfileCounts,
        accessibleBookIds = accessibleBookIds,
        resumableBookIds = resumableBookIds,
    )

    private companion object {
        val SERVER = ServerId("server-1")
    }
}
