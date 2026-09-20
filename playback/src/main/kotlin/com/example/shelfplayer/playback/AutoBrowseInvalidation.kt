package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.LocalAvailability
import com.example.shelfplayer.domain.library.booksInSeriesOrder
import com.example.shelfplayer.domain.library.homeShelvesOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan

/**
 * One profile generation observed by the Android Auto browse invalidation source.
 *
 * The generation changes only when active profile identity changes. Keeping it beside the opaque profile id
 * turns profile switching into an explicit hard boundary even when two profiles expose identical child counts.
 */
internal data class BrowseProfileScope(
    val profileId: ProfileId?,
    val generation: Long,
)

/**
 * Immutable Android Auto browse shape derived from one accessible-book emission.
 *
 * [childrenByParent] deliberately stores opaque ids rather than titles or other private media metadata. Order
 * is retained because reordering the same children is still an exposed host change. The current tree is built
 * by [CurrentAutoBrowseSnapshotBuilder]; the comparison engine does not know what "Library", "Downloads" or
 * any other destination means, so issue #65 can replace the adapter without replacing invalidation policy.
 */
internal data class AutoBrowseSnapshot(
    val scope: BrowseProfileScope,
    val childrenByParent: Map<String, List<String>>,
    val ordinaryParents: Set<String>,
    val profileScopedParents: Set<String>,
    val deferredProfileCounts: Set<String>,
    val accessibleBookIds: Set<LibraryItemId>,
)

/** One Media3 parent refresh. A null count is resolved without another complete library read. */
internal data class BrowseInvalidation(
    val parentId: String,
    val childCount: Int?,
)

internal data class BrowseInvalidationPlan(
    val profileBoundary: Boolean,
    val notifications: List<BrowseInvalidation>,
) {
    companion object {
        val None = BrowseInvalidationPlan(profileBoundary = false, notifications = emptyList())
    }
}

/**
 * Stateful comparison of already-published browse shape.
 *
 * Ordinary mutations compare ordered membership, not counts. A profile boundary deliberately ignores equality:
 * every profile-scoped static parent plus every dynamic parent ever emitted by this process is refreshed.
 */
internal class AutoBrowseInvalidationTracker {
    private var previous: AutoBrowseSnapshot? = null

    fun next(
        current: AutoBrowseSnapshot,
        emittedDynamicParents: Set<String>,
    ): BrowseInvalidationPlan {
        val before = previous
        previous = current
        if (before == null) return BrowseInvalidationPlan.None

        if (before.scope != current.scope) {
            val parents = before.profileScopedParents + current.profileScopedParents + emittedDynamicParents
            return BrowseInvalidationPlan(
                profileBoundary = true,
                notifications = parents.sorted().map { parentId ->
                    BrowseInvalidation(
                        parentId = parentId,
                        childCount = if (parentId in current.deferredProfileCounts) {
                            null
                        } else {
                            current.childrenByParent[parentId]?.size ?: 0
                        },
                    )
                },
            )
        }

        val candidates = before.childrenByParent.keys + current.childrenByParent.keys
        val changed = candidates
            .asSequence()
            .filter { parentId ->
                parentId in before.ordinaryParents ||
                    parentId in current.ordinaryParents ||
                    parentId in emittedDynamicParents
            }
            .filter { parentId ->
                before.childrenByParent[parentId].orEmpty() != current.childrenByParent[parentId].orEmpty()
            }
            .sorted()
            .map { parentId ->
                BrowseInvalidation(
                    parentId = parentId,
                    childCount = current.childrenByParent[parentId]?.size ?: 0,
                )
            }
            .toList()

        return BrowseInvalidationPlan(profileBoundary = false, notifications = changed)
    }
}

/**
 * Converts active-profile + Room emissions into exactly one fully-derived snapshot per candidate sweep.
 *
 * There is one [accessibleBooks] subscription for the active profile generation. Every parent decision in one
 * emitted snapshot is therefore derived from the same immutable [List] instead of asking the repository again
 * for each remembered Series/Author node.
 */
internal class AutoBrowseSnapshotSource(
    private val activeProfiles: Flow<ProfileId?>,
    private val accessibleBooks: (ProfileId) -> Flow<List<Book>>,
    private val build: (BrowseProfileScope, List<Book>) -> AutoBrowseSnapshot,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun snapshots(): Flow<AutoBrowseSnapshot> =
        activeProfiles
            .distinctUntilChanged()
            .scan(BrowseProfileScope(profileId = null, generation = 0L)) { previous, profileId ->
                BrowseProfileScope(profileId = profileId, generation = previous.generation + 1)
            }
            .drop(1)
            .flatMapLatest { scope ->
                scope.profileId?.let { profileId ->
                    accessibleBooks(profileId).map { books -> build(scope, books) }
                } ?: flowOf(build(scope, emptyList()))
            }
            .distinctUntilChanged()
}

/**
 * Adapter from today's Android Auto information architecture to the reusable shape model.
 *
 * Issue #65 is expected to replace this mapping when the visible tree becomes
 * Continue -> Series -> Authors -> Profiles. The tracker/source above should not need to change.
 */
internal object CurrentAutoBrowseSnapshotBuilder {
    private val ordinaryParents = setOf(
        AutoLibrary.ROOT,
        AutoLibrary.TAB_CONTINUE,
        AutoLibrary.TAB_LIBRARY,
        AutoLibrary.TAB_SERIES,
        AutoLibrary.TAB_AUTHORS,
        AutoLibrary.TAB_DOWNLOADS,
        AutoLibrary.TAB_RECENT,
        AutoLibrary.TAB_DISCOVER,
        AutoLibrary.TAB_AGAIN,
    )

    private val profileScopedParents = ordinaryParents + setOf(
        AutoLibrary.RECENT_ROOT,
        AutoLibrary.TAB_CHAPTERS,
        AutoLibrary.TAB_HISTORY,
    )

    private val deferredProfileCounts = setOf(
        AutoLibrary.RECENT_ROOT,
        AutoLibrary.TAB_CHAPTERS,
        AutoLibrary.TAB_HISTORY,
    )

    fun build(
        scope: BrowseProfileScope,
        books: List<Book>,
    ): AutoBrowseSnapshot {
        val shelves = homeShelvesOf(books)
        val series = books
            .flatMap(Book::seriesMemberships)
            .distinctBy { membership -> membership.series.id }
            .sortedBy { membership -> membership.series.name.lowercase() }
        val authors = books
            .flatMap(Book::authors)
            .distinctBy { author -> author.id }
            .sortedBy { author -> author.name.lowercase() }

        val downloaded = books
            .filter { book -> book.localAvailability == LocalAvailability.Complete }
            .map { book -> book.id.value }

        val children = linkedMapOf<String, List<String>>(
            AutoLibrary.ROOT to if (books.isEmpty()) {
                listOf(AutoLibrary.NOTICE_EMPTY)
            } else {
                listOf(
                    AutoLibrary.TAB_CONTINUE,
                    AutoLibrary.TAB_SERIES,
                    AutoLibrary.TAB_AUTHORS,
                    AutoLibrary.TAB_LIBRARY,
                )
            },
            AutoLibrary.TAB_CONTINUE to shelves.continueListening.map { book -> book.id.value },
            AutoLibrary.TAB_SERIES to series.map { membership -> membership.series.id.value },
            AutoLibrary.TAB_AUTHORS to authors.map { author -> author.id.value },
            AutoLibrary.TAB_LIBRARY to buildList {
                add(AutoLibrary.TAB_CHAPTERS)
                add(AutoLibrary.TAB_HISTORY)
                if (downloaded.isNotEmpty()) add(AutoLibrary.TAB_DOWNLOADS)
                if (shelves.recentlyAdded.isNotEmpty()) add(AutoLibrary.TAB_RECENT)
                if (shelves.listenAgain.isNotEmpty()) add(AutoLibrary.TAB_AGAIN)
                if (shelves.discover.isNotEmpty()) add(AutoLibrary.TAB_DISCOVER)
                add(AutoLibrary.TAB_OUTPUT)
            },
            AutoLibrary.TAB_DOWNLOADS to downloaded,
            AutoLibrary.TAB_RECENT to shelves.recentlyAdded.map { book -> book.id.value },
            AutoLibrary.TAB_AGAIN to shelves.listenAgain.map { book -> book.id.value },
            AutoLibrary.TAB_DISCOVER to shelves.discover.map { book -> book.id.value },
        )

        series.forEach { membership ->
            children["${AutoLibrary.SERIES_PREFIX}${membership.series.id.value}"] =
                booksInSeriesOrder(books, membership).map { book -> book.id.value }
        }
        authors.forEach { author ->
            children["${AutoLibrary.AUTHOR_PREFIX}${author.id.value}"] = books
                .filter { book -> book.authors.any { candidate -> candidate.id == author.id } }
                .sortedBy { book -> book.title.lowercase() }
                .map { book -> book.id.value }
        }

        return AutoBrowseSnapshot(
            scope = scope,
            childrenByParent = children,
            ordinaryParents = ordinaryParents,
            profileScopedParents = profileScopedParents,
            deferredProfileCounts = deferredProfileCounts,
            accessibleBookIds = books.mapTo(linkedSetOf()) { book -> book.id },
        )
    }
}
