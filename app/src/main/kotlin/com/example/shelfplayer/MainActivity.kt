package com.example.shelfplayer

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.core.designsystem.theme.ShelfPlayerTheme
import com.example.shelfplayer.feature.browse.LocalAuthorUrls
import com.example.shelfplayer.feature.browse.LocalCoverUrls
import com.example.shelfplayer.feature.browse.authorUrlsFor
import com.example.shelfplayer.feature.browse.coverUrlsFor
import com.example.shelfplayer.feature.lock.LockCurtain
import com.example.shelfplayer.feature.lock.LockViewModel
import com.example.shelfplayer.feature.lock.RecentsPrivacy
import com.example.shelfplayer.feature.player.BookmarkAddedNotice
import com.example.shelfplayer.feature.player.BookmarkSheet
import com.example.shelfplayer.feature.player.ChapterSheet
import com.example.shelfplayer.feature.player.FullPlayer
import com.example.shelfplayer.feature.player.HistorySheet
import com.example.shelfplayer.feature.player.MINI_PLAYER_HEIGHT
import com.example.shelfplayer.feature.player.MiniPlayer
import com.example.shelfplayer.feature.player.OutputControls
import com.example.shelfplayer.feature.player.PlayerActions
import com.example.shelfplayer.feature.player.PlayerViewModel
import com.example.shelfplayer.feature.player.RewindNotice
import com.example.shelfplayer.feature.player.SkipControls
import com.example.shelfplayer.feature.player.SleepTimerSheet
import com.example.shelfplayer.feature.player.SpeedSheet
import com.example.shelfplayer.navigation.ShelfDestinations
import com.example.shelfplayer.navigation.ShelfPlayerNavHost
import com.example.shelfplayer.ui.glass.LocalGlassHazeState
import com.example.shelfplayer.ui.glass.LocalPlayerChromeBottomInset
import dagger.hilt.android.AndroidEntryPoint
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * PRODUCT_SPEC 4 — adaptive, never orientation-locked.
 *
 * The activity is a thin shell: it resolves the theme and hosts the navigation graph. PRODUCT_SPEC
 * PLAY-001 requires playback to survive this activity being destroyed, so nothing that outlives a
 * screen may be owned here — the mini player below reads the media session's state and owns none of it.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        /*
         * PRODUCT_SPEC AUTH-005 / §15 / 14.5, ADR-0026 decision 3 — keep the library out of Recents.
         *
         * The app-switcher thumbnail is a screenshot of whatever was last on screen, which for this app is
         * a list of book titles. Suppressing just that image is the narrow control; `FLAG_SECURE` is the
         * blunt one and ADR-0026 declined it, because it would also block every deliberate screenshot and
         * screen recording for every user. See [RecentsPrivacy] for the whole reasoning, including what
         * deliberately does not happen below API 33.
         */
        if (RecentsPrivacy.isSupported()) {
            setRecentsScreenshotEnabled(false)
        }
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: AppViewModel = hiltViewModel()
            val appState by viewModel.state.collectAsStateWithLifecycle()
            // PRODUCT_SPEC SET-002 — outside the theme, because it decides what every string below says
            // and the theme only decides what colour it is drawn in.
            AppLocale(language = appState.language) {
                ShelfPlayerTheme(
                    darkTheme = appState.resolveDarkTheme(systemInDarkTheme = isSystemInDarkTheme()),
                    dynamicColor = appState.dynamicColor,
                ) {
                    // The graph is not composed until the start destination is known. Composing it early
                    // and correcting it would show a flash of the wrong screen on every cold start, and
                    // would put a spurious entry in the back stack.
                    if (appState.isResolved) {
                        // PRODUCT_SPEC LIB-004 — provided once, here, because every shelf renders covers
                        // and the address they need belongs to the server row rather than to any one
                        // screen.
                        CompositionLocalProvider(
                            LocalCoverUrls provides coverUrlsFor(appState.serverBaseUrls),
                            LocalAuthorUrls provides authorUrlsFor(appState.serverBaseUrls),
                        ) {
                            ShelfPlayerContent(
                                startDestination = if (appState.hasAnyProfile) {
                                    ShelfDestinations.HOME
                                } else {
                                    ShelfDestinations.SIGN_IN
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The navigation graph with the mini player floating over it.
 *
 * The old column put the player in its own opaque strip, which meant there was literally nothing behind
 * the bar to frost. The graph now remains full-height as the Haze source and the player is an overlay; Home
 * receives the player's height separately so its floating axis bar still moves clear of the controls.
 */
@Composable
private fun ShelfPlayerContent(
    startDestination: String,
    playerViewModel: PlayerViewModel = hiltViewModel(),
    lockViewModel: LockViewModel = hiltViewModel(),
) {
    val lockState by lockViewModel.state.collectAsStateWithLifecycle()

    // AUTH-005 — the curtain replaces the app rather than covering it.
    //
    // An overlay would leave `MiniPlayer`'s title in the semantics tree, where it is marked as a polite
    // live region — so TalkBack would read the locked account's book aloud over the passcode field, and its
    // stop button and tap-to-expand would stay reachable. A lock that announces what it protects is not a
    // lock. `Resolving` draws neither: showing the shelf for one frame before the curtain arrives is the
    // exact leak this exists to prevent, and showing a passcode field to the majority who have none is the
    // other way to be wrong.
    when {
        lockState.locked != null -> {
            LockCurtain()
            return
        }

        !lockState.isResolved -> return
    }

    val playback by playerViewModel.playback.collectAsStateWithLifecycle()
    val timer by playerViewModel.timer.collectAsStateWithLifecycle()
    val isExpanded by playerViewModel.isExpanded.collectAsStateWithLifecycle()
    val isNotificationBlocked by playerViewModel.isNotificationBlocked.collectAsStateWithLifecycle()
    val playbackSettings by playerViewModel.settings.collectAsStateWithLifecycle()
    val rewind by playerViewModel.rewind.collectAsStateWithLifecycle()
    // PRODUCT_SPEC PLAY-002 — the outputs the book can be sent to, for the chooser in the player's top bar.
    val audioOutputs by playerViewModel.outputs.collectAsStateWithLifecycle()
    val selectedOutput by playerViewModel.selectedOutput.collectAsStateWithLifecycle()
    val history by playerViewModel.history.collectAsStateWithLifecycle()
    val bookmarks by playerViewModel.bookmarkList.collectAsStateWithLifecycle()
    val bookmarkAdded by playerViewModel.bookmarkAdded.collectAsStateWithLifecycle()
    val playbackMessage by playerViewModel.message.collectAsStateWithLifecycle()
    val skipControls = SkipControls(
        intervals = playbackSettings.skips,
        onBack = playerViewModel::onSkipBack,
        onForward = playerViewModel::onSkipForward,
    )
    var isTimerSheetOpen by remember { mutableStateOf(false) }
    var isChapterSheetOpen by remember { mutableStateOf(false) }
    var isSpeedSheetOpen by remember { mutableStateOf(false) }
    var isHistorySheetOpen by remember { mutableStateOf(false) }
    var isBookmarkSheetOpen by remember { mutableStateOf(false) }
    val hazeState = remember { HazeState() }
    val playerChromeInset by animateDpAsState(
        targetValue = if (playback.bookId != null) MINI_PLAYER_HEIGHT else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "mini-player-inset",
    )
    NotificationPermission(hasPlayback = playback.bookId != null)
    SyncOnBackground(onBackgrounded = playerViewModel::onAppBackgrounded)

    CompositionLocalProvider(
        LocalGlassHazeState provides hazeState,
        LocalPlayerChromeBottomInset provides playerChromeInset,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ShelfPlayerNavHost(
                startDestination = startDestination,
                onBookPlaySelected = playerViewModel::onPlayFromShelf,
                playbackMessage = playbackMessage,
                onPlaybackMessageShown = playerViewModel::onMessageShown,
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState),
            )
            MiniPlayer(
                state = playback,
                timer = timer,
                onTogglePlayPause = playerViewModel::onTogglePlayPause,
                onStop = playerViewModel::onStop,
                onOpenSleepTimer = { isTimerSheetOpen = true },
                onExpand = playerViewModel::onExpand,
                skips = skipControls,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    // PRODUCT_SPEC PLAY-001 — the full player, over everything.
    //
    // Drawn after the chrome rather than instead of it, so collapsing reveals the screen the listener
    // was on with its scroll intact. A book that stopped while the player was open collapses it: an
    // expanded player showing nothing has no content and no obvious way out.
    if (isExpanded && playback.bookId != null) {
        FullPlayer(
            state = playback,
            timer = timer,
            isNotificationBlocked = isNotificationBlocked,
            skips = skipControls,
            // PRODUCT_SPEC PLAY-002 — collected here rather than inside the player, so the list and the
            // callback that acts on it come from the one view model that owns the session.
            outputs = OutputControls(
                outputs = audioOutputs,
                selectedId = selectedOutput,
                onSelect = playerViewModel::onOutputSelected,
            ),
            actions = PlayerActions(
                onTogglePlayPause = playerViewModel::onTogglePlayPause,
                onSeekTo = playerViewModel::onSeekTo,
                onOpenSleepTimer = { isTimerSheetOpen = true },
                onOpenChapters = { isChapterSheetOpen = true },
                onOpenSpeed = { isSpeedSheetOpen = true },
                onCollapse = playerViewModel::onCollapse,
                onRetry = playerViewModel::onRetry,
                onOpenHistory = {
                    isHistorySheetOpen = true
                    // PRODUCT_SPEC PLAY-003 — pull the server's own session records in as the pane opens.
                    // Persisted, so the pane fills from Room and the rows stay when the network does not.
                    playerViewModel.onOpenHistory()
                },
                onOpenBookmarks = { isBookmarkSheetOpen = true },
                onAddBookmark = playerViewModel::onAddBookmark,
            ),
        )
    }

    // PRODUCT_SPEC PLAY-003 — the jumps this book has seen. Tapping one returns to where it started, which
    // is the only undo a seek has ever had.
    if (isHistorySheetOpen) {
        HistorySheet(
            entries = history,
            // PRODUCT_SPEC PLAY-003 — so a row can say which chapter it happened in. The player already
            // holds the list; the history repository deliberately does not, because a chapter name stored
            // beside every event would be the same forty strings written down a hundred times.
            chapters = playback.chapters,
            onReturnTo = playerViewModel::onSeekTo,
            onDismiss = { isHistorySheetOpen = false },
        )
    }

    // PRODUCT_SPEC 11.1 — the places this listener kept in this book.
    if (isBookmarkSheetOpen) {
        BookmarkSheet(
            bookmarks = bookmarks,
            // The chapter a bookmark falls in, for the same reason the history pane gets it: the player
            // holds the chapter list and the bookmark table deliberately does not.
            chapters = playback.chapters,
            // PRODUCT_SPEC 11.1 — the sheet offers "New bookmark at …", so it needs to know where the book
            // is. Read from the same state the seek bar draws, so the label and the write agree.
            position = playback.position,
            actions = playerViewModel.bookmarkActions,
            onDismiss = { isBookmarkSheetOpen = false },
        )
    }

    // PRODUCT_SPEC 11.1 / 21 — a long press keeps a spot without opening anything, so something has to
    // say it worked. The position is in the message because that is what a listener would check.
    bookmarkAdded?.let { at ->
        BookmarkAddedNotice(at = at, onDismiss = playerViewModel::onBookmarkAddedShown)
    }

    if (isChapterSheetOpen) {
        ChapterSheet(
            chapters = playback.chapters,
            current = playback.currentChapter,
            onSelect = { chapter ->
                playerViewModel.onChapterSelected(chapter)
                isChapterSheetOpen = false
            },
            onDismiss = { isChapterSheetOpen = false },
        )
    }

    if (isSpeedSheetOpen) {
        SpeedSheet(
            speed = playback.speed,
            onSelect = { chosen ->
                playerViewModel.onSpeedSelected(chosen)
            },
            onReset = {
                playerViewModel.onSpeedCleared()
                isSpeedSheetOpen = false
            },
            onDismiss = { isSpeedSheetOpen = false },
        )
    }

    // PRODUCT_SPEC PLAY-009 — "applied rewind is visible briefly and can be undone".
    rewind?.let { applied ->
        RewindNotice(
            applied = applied,
            onUndo = playerViewModel::onUndoRewind,
            onDismiss = playerViewModel::onRewindNoticeShown,
        )
    }

    if (isTimerSheetOpen) {
        SleepTimerSheet(
            state = timer,
            onSelect = { mode ->
                playerViewModel.onSleepTimerSelected(mode)
                isTimerSheetOpen = false
            },
            onDismiss = { isTimerSheetOpen = false },
        )
    }
}

/**
 * PRODUCT_SPEC PLAY-004 — "app background transition when possible".
 *
 * `ON_STOP` rather than `ON_PAUSE`: pausing happens for a dialog too, and a sync per dialog is a lot of
 * requests for no new information. Stopping is the app actually leaving the foreground.
 *
 * The observer is removed in `onDispose`, which is what stops a recomposition from leaving a second one
 * registered — the classic version of this bug syncs twice per background transition after a rotation.
 */
@Composable
private fun SyncOnBackground(onBackgrounded: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) onBackgrounded()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/**
 * PRODUCT_SPEC PLAY-001 / 15 — asks for the notification permission the first time a book plays.
 *
 * Not at launch. A permission prompt on a screen with no audio on it is a prompt with no context, and
 * the honest answer to "why does this want notifications" is "so you can pause the book from your lock
 * screen" — which is only true once there is a book. Playback itself does not depend on the grant: the
 * foreground service starts either way, and a refusal costs the notification, not the audio.
 *
 * Below API 33 the permission does not exist and nothing is asked.
 */
@Composable
private fun NotificationPermission(hasPlayback: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(hasPlayback) {
        if (hasPlayback) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
