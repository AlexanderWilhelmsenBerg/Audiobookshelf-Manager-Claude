package com.example.shelfplayer

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.core.designsystem.theme.ShelfPlayerTheme
import com.example.shelfplayer.feature.browse.LocalCoverUrls
import com.example.shelfplayer.feature.browse.coverUrlsFor
import com.example.shelfplayer.feature.player.ChapterSheet
import com.example.shelfplayer.feature.player.FullPlayer
import com.example.shelfplayer.feature.player.MiniPlayer
import com.example.shelfplayer.feature.player.PlayerActions
import com.example.shelfplayer.feature.player.PlayerViewModel
import com.example.shelfplayer.feature.player.RewindNotice
import com.example.shelfplayer.feature.player.SkipControls
import com.example.shelfplayer.feature.player.SleepTimerSheet
import com.example.shelfplayer.feature.player.SpeedSheet
import com.example.shelfplayer.navigation.ShelfDestinations
import com.example.shelfplayer.navigation.ShelfPlayerNavHost
import dagger.hilt.android.AndroidEntryPoint

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
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: AppViewModel = hiltViewModel()
            val appState by viewModel.state.collectAsStateWithLifecycle()
            ShelfPlayerTheme(
                darkTheme = appState.resolveDarkTheme(systemInDarkTheme = isSystemInDarkTheme()),
                dynamicColor = appState.dynamicColor,
            ) {
                // The graph is not composed until the start destination is known. Composing it early and
                // correcting it would show a flash of the wrong screen on every cold start, and would put
                // a spurious entry in the back stack.
                if (appState.isResolved) {
                    // PRODUCT_SPEC LIB-004 — provided once, here, because every shelf renders covers and
                    // the address they need belongs to the server row rather than to any one screen.
                    CompositionLocalProvider(
                        LocalCoverUrls provides coverUrlsFor(appState.serverBaseUrls),
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

/**
 * The navigation graph, with the mini player pinned beneath it.
 *
 * A [Column] rather than a `Scaffold` bottom bar: every screen already has its own `Scaffold`, and
 * nesting one inside another is how insets end up applied twice. The bar contributes no height at all
 * when nothing is playing — see [MiniPlayer].
 */
@Composable
private fun ShelfPlayerContent(startDestination: String, playerViewModel: PlayerViewModel = hiltViewModel()) {
    val playback by playerViewModel.playback.collectAsStateWithLifecycle()
    val timer by playerViewModel.timer.collectAsStateWithLifecycle()
    val isExpanded by playerViewModel.isExpanded.collectAsStateWithLifecycle()
    val isNotificationBlocked by playerViewModel.isNotificationBlocked.collectAsStateWithLifecycle()
    val playbackSettings by playerViewModel.settings.collectAsStateWithLifecycle()
    val rewind by playerViewModel.rewind.collectAsStateWithLifecycle()
    val skipControls = SkipControls(
        intervals = playbackSettings.skips,
        onBack = playerViewModel::onSkipBack,
        onForward = playerViewModel::onSkipForward,
    )
    var isTimerSheetOpen by remember { mutableStateOf(false) }
    var isChapterSheetOpen by remember { mutableStateOf(false) }
    var isSpeedSheetOpen by remember { mutableStateOf(false) }
    NotificationPermission(hasPlayback = playback.bookId != null)
    SyncOnBackground(onBackgrounded = playerViewModel::onAppBackgrounded)

    Column(modifier = Modifier.fillMaxSize()) {
        ShelfPlayerNavHost(
            startDestination = startDestination,
            modifier = Modifier.weight(WEIGHT_FILL),
        )
        MiniPlayer(
            state = playback,
            timer = timer,
            onTogglePlayPause = playerViewModel::onTogglePlayPause,
            onStop = playerViewModel::onStop,
            onOpenSleepTimer = { isTimerSheetOpen = true },
            onExpand = playerViewModel::onExpand,
            skips = skipControls,
        )
    }

    // PRODUCT_SPEC PLAY-001 — the full player, over everything.
    //
    // Drawn after the column rather than instead of it, so collapsing reveals the screen the listener
    // was on with its scroll intact. A book that stopped while the player was open collapses it: an
    // expanded player showing nothing has no content and no obvious way out.
    if (isExpanded && playback.bookId != null) {
        FullPlayer(
            state = playback,
            timer = timer,
            isNotificationBlocked = isNotificationBlocked,
            skips = skipControls,
            actions = PlayerActions(
                onTogglePlayPause = playerViewModel::onTogglePlayPause,
                onSeekTo = playerViewModel::onSeekTo,
                onOpenSleepTimer = { isTimerSheetOpen = true },
                onOpenChapters = { isChapterSheetOpen = true },
                onOpenSpeed = { isSpeedSheetOpen = true },
                onCollapse = playerViewModel::onCollapse,
                onRetry = playerViewModel::onRetry,
            ),
        )
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

/** The navigation graph takes every pixel the mini player does not. */
private const val WEIGHT_FILL = 1f
