package com.example.shelfplayer.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.LogLevel
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.time.AppClock
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlin.time.Duration

/**
 * PRODUCT_SPEC 11.1 / AUTH-002 / #289 - read-only MediaLibrarySession browse protocol.
 * Player snapshots are taken synchronously at callback entry; suspended library reads use only values.
 * Search notification and pagination remain here, while playback and profile selection have separate owners.
 */
internal class LibraryBrowseCallbacks(
    private val auto: AutoLibrary,
    private val futures: SessionCallbackFutures,
    private val autoTraces: AndroidAutoTraceRecorder,
    private val clock: AppClock,
    private val logger: Logger,
    private val nowPlaying: () -> NowPlaying?,
    private val player: () -> Player?,
    private val mayBrowse: (MediaSession, MediaSession.ControllerInfo) -> Boolean,
    private val carPackages: Set<String>,
) {
    private data class ChildrenTraceContext(
        val parentId: String,
        val page: Int,
        val pageSize: Int,
        val allCount: Int,
        val entryPlayer: List<LogField>,
        val startedAt: Duration,
    )

    /**
     * PRODUCT_SPEC 11.1 — the browse root, which is what makes the app appear in a car at all.
     *
     * The default rejects, and a rejection is what wave 1 shipped because an empty root would have
     * looked supported and browsed to nothing. There is a tree now.
     */
    fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        // PRODUCT_SPEC ROUTE-001 — the car asking for a *resume tile* rather than for the browse tree.
        //
        // Android Auto sends this hint when it starts, before the driver has touched anything, and what
        // comes back is the tile on the media home screen. It is a different question from "what can I
        // browse", so it gets a different root: see `AutoLibrary.RECENT_ROOT`.
        if (!mayBrowse(session, browser)) {
            return Futures.immediateFuture(deniedItem(browser, "onGetLibraryRoot"))
        }
        val root = if (params?.isRecent == true) auto.recentRoot() else auto.root()
        autoTraces.forController(browser.packageName, carPackages)?.let { trace ->
            autoTraces.log(
                "Android Auto library root returned",
                trace,
                buildList {
                    add(LogField.Public("recent", params?.isRecent == true))
                    addAll(AndroidAutoDiagnostics.playerFields(player()))
                    addAll(AndroidAutoDiagnostics.itemFields(root, prefix = "returned"))
                },
            )
        }
        return Futures.immediateFuture(LibraryResult.ofItem(root, params))
    }

    fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        // Capture synchronously at Media3 callback entry. The future currently completes on main,
        // but browse work must not gain permission to read Player after suspension or a dispatcher change.
        // The plain snapshot also keeps the requested book/position pair coherent (R-66 / ADR-0016).
        val now = nowPlaying()
        val trace = autoTraces.forController(browser.packageName, carPackages)
        val entryPlayer = if (trace == null) emptyList() else AndroidAutoDiagnostics.playerFields(player())
        val startedAt = clock.elapsed()
        return futures.submit {
            if (!mayBrowse(session, browser)) return@submit deniedList(browser, "onGetChildren")
            val all = auto.children(parentId, now)
            logger.info(
                LogCategory.Playback,
                "A browser asked for a node's children",
                LogField.Public("parent", parentId),
                LogField.Count("children", all.size),
            )
            val from = (page * pageSize).coerceAtMost(all.size)
            val to = (from + pageSize).coerceAtMost(all.size)
            val returned = all.subList(from, to)
            logger.info(
                LogCategory.Playback,
                "Android Auto browse artwork offered",
                LogField.Public("parentKind", AutoLibrary.kindOf(parentId)),
                LogField.Count("returned", returned.size),
                LogField.Count("withArtworkUri", returned.count { item -> item.mediaMetadata.artworkUri != null }),
                LogField.Count(
                    "withContentArtworkUri",
                    returned.count { item -> item.mediaMetadata.artworkUri?.scheme == "content" },
                ),
            )
            // Only sample the first grid cards the car is likely to display, not whole libraries.
            // A matching artworkRef in the content-provider log proves that the host requested that card.
            if (trace != null) {
                returned.take(AndroidAutoDiagnostics.MAX_ARTWORK_OFFER_EVENTS).forEachIndexed { index, item ->
                    val fields = AndroidAutoDiagnostics.artworkOfferFields(parentId, from + index, item)
                    if (fields.isNotEmpty()) {
                        logger.log(
                            LogEvent(
                                LogLevel.Info,
                                LogCategory.Playback,
                                "Android Auto artwork card offered",
                                fields,
                            ),
                        )
                    }
                }
            }
            traceChildrenResult(
                trace = trace,
                context = ChildrenTraceContext(
                    parentId = parentId,
                    page = page,
                    pageSize = pageSize,
                    allCount = all.size,
                    entryPlayer = entryPlayer,
                    startedAt = startedAt,
                ),
                returned = returned,
            )
            LibraryResult.ofItemList(ImmutableList.copyOf(returned), params)
        }
    }

    private fun traceChildrenResult(
        trace: AndroidAutoTraceRecorder.Trace?,
        context: ChildrenTraceContext,
        returned: List<MediaItem>,
    ) {
        if (trace == null) return
        val parentFields = listOf(
            LogField.Public("parentKind", AutoLibrary.kindOf(context.parentId)),
            LogField.Identifier("parentId", context.parentId),
        )
        autoTraces.log(
            "Android Auto children returned",
            trace,
            buildList {
                addAll(parentFields)
                add(LogField.Public("page", context.page))
                add(LogField.Public("pageSize", context.pageSize))
                add(LogField.Count("children", context.allCount))
                add(LogField.Count("returned", returned.size))
                add(
                    LogField.Millis(
                        "callbackElapsed",
                        (clock.elapsed() - context.startedAt).inWholeMilliseconds.coerceAtLeast(0L),
                    ),
                )
                addAll(context.entryPlayer)
            },
        )
        autoTraces.logItems(
            trace = trace,
            callback = "onGetChildren",
            direction = "returned",
            items = returned,
            extraFields = parentFields,
        )
    }

    fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val now = nowPlaying()
        val trace = autoTraces.forController(browser.packageName, carPackages)
        val entryPlayer = if (trace == null) emptyList() else AndroidAutoDiagnostics.playerFields(player())
        val startedAt = clock.elapsed()
        return futures.submit {
            if (!mayBrowse(session, browser)) return@submit deniedItem(browser, "onGetItem")
            val resolved = auto.item(mediaId, now)
            if (trace != null) {
                autoTraces.log(
                    "Android Auto item lookup returned",
                    trace,
                    buildList {
                        add(LogField.Public("askedKind", AutoLibrary.kindOf(mediaId)))
                        add(LogField.Identifier("askedId", mediaId))
                        add(LogField.Public("resolved", resolved != null))
                        add(
                            LogField.Millis(
                                "callbackElapsed",
                                (clock.elapsed() - startedAt).inWholeMilliseconds.coerceAtLeast(0L),
                            ),
                        )
                        addAll(entryPlayer)
                        addAll(AndroidAutoDiagnostics.itemFields(resolved, prefix = "returned"))
                    },
                )
            }
            resolved
                ?.let { item -> LibraryResult.ofItem(item, null) }
                ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }
    }

    /**
     * Media3 search is a two-step protocol: this callback announces how many results are available,
     * then [onGetSearchResult] supplies the requested page. Returning items directly from `onSearch`
     * would skip the notification a browser is waiting for and leave Android Auto showing no results.
     */
    @Suppress("ForbiddenVoid")
    fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = futures.submit {
        if (!mayBrowse(session, browser)) return@submit deniedVoid(browser, "onSearch")
        val results = auto.search(query)
        session.notifySearchResultChanged(browser, query, results.size, params)
        LibraryResult.ofVoid(params)
    }

    fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val trace = autoTraces.forController(browser.packageName, carPackages)
        val startedAt = clock.elapsed()
        return futures.submit {
            if (!mayBrowse(session, browser)) return@submit deniedList(browser, "onGetSearchResult")
            val all = auto.search(query)
            val from = (page * pageSize).coerceAtMost(all.size)
            val to = (from + pageSize).coerceAtMost(all.size)
            val returned = all.subList(from, to)
            autoTraces.logItems(
                trace = trace,
                callback = "onGetSearchResult",
                direction = "returned",
                items = returned,
                extraFields = listOf(
                    LogField.Public("page", page),
                    LogField.Public("pageSize", pageSize),
                    LogField.Count("results", all.size),
                    LogField.Millis(
                        "callbackElapsed",
                        (clock.elapsed() - startedAt).inWholeMilliseconds.coerceAtLeast(0L),
                    ),
                ),
            )
            LibraryResult.ofItemList(ImmutableList.copyOf(returned), params)
        }
    }

    private fun denied(browser: MediaSession.ControllerInfo, callback: String) {
        logger.info(
            LogCategory.Playback,
            "A controller without library access was refused",
            LogField.Public("controller", browser.packageName),
            LogField.Public("request", callback),
        )
    }

    private fun deniedItem(browser: MediaSession.ControllerInfo, callback: String): LibraryResult<MediaItem> {
        denied(browser, callback)
        return LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED)
    }

    private fun deniedList(
        browser: MediaSession.ControllerInfo,
        callback: String,
    ): LibraryResult<ImmutableList<MediaItem>> {
        denied(browser, callback)
        return LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED)
    }

    @Suppress("ForbiddenVoid")
    private fun deniedVoid(browser: MediaSession.ControllerInfo, callback: String): LibraryResult<Void> {
        denied(browser, callback)
        return LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED)
    }
}
