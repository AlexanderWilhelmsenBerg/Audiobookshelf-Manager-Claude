package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.domain.library.homeShelvesOf
import com.example.shelfplayer.domain.library.resumeCandidate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
internal data class BrowseProfileScope(val profileId: ProfileId?, val generation: Long)

/**
 * Immutable Android Auto browse shape derived from one accessible-book emission.
 *
 * [childrenByParent] deliberately stores opaque ids rather than titles or other private media metadata. Order
 * is retained because reordering the same children is still an exposed host change. The current tree is built
 * by [AutoBrowseSnapshotBuilder]; the comparison engine does not know what "Library", "Downloads" or
 * any other destination means, so issue #65 can replace the adapter without replacing invalidation policy.
 */
internal data class AutoBrowseSnapshot(
    val scope: BrowseProfileScope,
    val childrenByParent: Map<String, List<String>>,
    val ordinaryParents: Set<String>,
    val profileScopedParents: Set<String>,
    val deferredProfileCounts: Set<String>,
    val accessibleBookIds: Set<LibraryItemId>,
    /** Books eligible for a resume identity, including a local remembered book with no cached progress. */
    val resumableBookIds: Set<LibraryItemId>,
)

/** Derives one [AutoBrowseSnapshot] from one emission of every input the browse tree depends on. */
internal typealias BrowseSnapshotBuild =
    (BrowseProfileScope, List<Book>, List<Profile>, List<Server>, LibraryItemId?) -> AutoBrowseSnapshot

/** One Media3 parent refresh. A null count is resolved without another complete library read. */
internal data class BrowseInvalidation(val parentId: String, val childCount: Int?)

internal data class BrowseInvalidationPlan(val profileBoundary: Boolean, val notifications: List<BrowseInvalidation>) {
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

    fun next(current: AutoBrowseSnapshot, emittedDynamicParents: Set<String>): BrowseInvalidationPlan {
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
 * Converts profile/library emissions into exactly one fully-derived snapshot per candidate sweep.
 *
 * #10's invariant remains intact: there is one [accessibleBooks] subscription for the active profile
 * generation, and every library-backed parent decision in one emitted snapshot comes from that same immutable
 * [List]; the device-local remembered book joins the same snapshot so a change of the resume tile's book
 * within one profile invalidates [AutoLibrary.RECENT_ROOT]. #65 adds the saved-profile presentation facts to
 * the same snapshot rather than creating a second
 * invalidation loop for the Profiles destination. Lock eligibility is point-read when rows/actions are served so
 * the security decision cannot be made stale by a cached presentation token.
 */
internal class AutoBrowseSnapshotSource(
    private val activeProfiles: Flow<ProfileId?>,
    private val savedProfiles: Flow<List<Profile>>,
    private val savedServers: Flow<List<Server>>,
    private val accessibleBooks: (ProfileId) -> Flow<List<Book>>,
    private val rememberedBook: (ProfileId) -> Flow<LibraryItemId?>,
    private val build: BrowseSnapshotBuild,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun snapshots(): Flow<AutoBrowseSnapshot> = activeProfiles
        .distinctUntilChanged()
        .scan(BrowseProfileScope(profileId = null, generation = 0L)) { previous, profileId ->
            BrowseProfileScope(profileId = profileId, generation = previous.generation + 1)
        }
        .drop(1)
        .flatMapLatest { scope ->
            val books = scope.profileId
                ?.let(accessibleBooks)
                ?: flowOf(emptyList())
            val remembered = scope.profileId
                ?.let(rememberedBook)
                ?: flowOf(null)
            combine(books, savedProfiles, savedServers, remembered) { all, profiles, servers, rememberedId ->
                build(scope, all, profiles, servers, rememberedId)
            }
        }
        .distinctUntilChanged()
}

/**
 * PD-001 adapter from BookWave's current profile/library state to #10's reusable shape model.
 *
 * The root is intentionally constant — even an account with no books must still be able to reach Profiles.
 * Retired pre-PD-001 parents remain only in [profileScopedParents] so a hard profile boundary can tell a host
 * caching an old tree that those nodes now have zero children. They are not ordinary parents and are never
 * rendered again.
 */
internal object AutoBrowseSnapshotBuilder {
    private val ordinaryParents = setOf(
        AutoLibrary.ROOT,
        AutoLibrary.TAB_CONTINUE,
        AutoLibrary.TAB_SERIES,
        AutoLibrary.TAB_AUTHORS,
        AutoLibrary.TAB_PROFILES,
        AutoLibrary.RECENT_ROOT,
    )

    private val retiredProfileScopedParents = setOf(
        AutoLibrary.TAB_LIBRARY,
        AutoLibrary.TAB_CHAPTERS,
        AutoLibrary.TAB_HISTORY,
        AutoLibrary.TAB_DOWNLOADS,
        AutoLibrary.TAB_RECENT,
        AutoLibrary.TAB_DISCOVER,
        AutoLibrary.TAB_AGAIN,
        AutoLibrary.TAB_OUTPUT,
    )

    private val profileScopedParents = ordinaryParents + retiredProfileScopedParents

    fun build(
        scope: BrowseProfileScope,
        books: List<Book>,
        profiles: List<Profile>,
        servers: List<Server>,
        rememberedId: LibraryItemId?,
    ): AutoBrowseSnapshot {
        val shelves = homeShelvesOf(books)
        val series = autoSeriesNodes(books)
        val authors = autoAuthorNodes(books)
        val serverNames = servers.associate { server -> server.id to server.displayName }

        val children = linkedMapOf<String, List<String>>(
            AutoLibrary.ROOT to listOf(
                AutoLibrary.TAB_CONTINUE,
                AutoLibrary.TAB_SERIES,
                AutoLibrary.TAB_AUTHORS,
                AutoLibrary.TAB_PROFILES,
            ),
            // The resume tile's single child: the same choice AutoLibrary.lastPlayed() serves, by opaque id only.
            AutoLibrary.RECENT_ROOT to listOfNotNull(resumeCandidate(books, rememberedId)?.id?.value),
            AutoLibrary.TAB_CONTINUE to shelves.continueListening.map { book -> book.id.value },
            AutoLibrary.TAB_SERIES to series.map { node -> node.membership.series.id.value },
            AutoLibrary.TAB_AUTHORS to authors.map { node -> node.author.id.value },
            AutoLibrary.TAB_PROFILES to profiles.map { profile ->
                profileFingerprint(
                    profile = profile,
                    serverName = serverNames[profile.serverId],
                    isActive = profile.id == scope.profileId,
                )
            },
        )

        series.forEach { node ->
            children["${AutoLibrary.SERIES_PREFIX}${node.membership.series.id.value}"] =
                node.books.map { book -> book.id.value }
        }
        authors.forEach { node ->
            children["${AutoLibrary.AUTHOR_PREFIX}${node.author.id.value}"] =
                node.books.map { book -> book.id.value }
        }

        return AutoBrowseSnapshot(
            scope = scope,
            childrenByParent = children,
            ordinaryParents = ordinaryParents,
            profileScopedParents = profileScopedParents,
            deferredProfileCounts = setOf(AutoLibrary.RECENT_ROOT),
            accessibleBookIds = books.mapTo(linkedSetOf()) { book -> book.id },
            resumableBookIds = books
                .asSequence()
                .filter { book -> book.progress?.isFinished != true }
                .mapTo(linkedSetOf()) { book -> book.id },
        )
    }

    /**
     * Opaque comparison token only. It is never logged or rendered, and deliberately excludes credentials,
     * URLs and media titles while still making visible profile-row changes invalidate the Profiles parent.
     */
    private fun profileFingerprint(profile: Profile, serverName: String?, isActive: Boolean): String = listOf(
        profile.id.value,
        profile.displayName.hashCode(),
        profile.username.hashCode(),
        serverName.orEmpty().hashCode(),
        profile.role.name,
        profile.requiresReauthentication,
        isActive,
    ).joinToString(separator = ":")
}
