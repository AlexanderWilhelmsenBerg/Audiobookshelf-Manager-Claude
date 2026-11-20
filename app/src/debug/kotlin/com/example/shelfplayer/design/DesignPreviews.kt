package com.example.shelfplayer.design

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.view.WindowCompat
import com.example.shelfplayer.R
import com.example.shelfplayer.core.designsystem.theme.BookWaveDesign
import com.example.shelfplayer.core.designsystem.theme.ShelfPlayerTheme
import com.example.shelfplayer.core.model.playback.SleepTimerState
import com.example.shelfplayer.core.model.settings.AppDesign
import com.example.shelfplayer.feature.browse.CoverUrls
import com.example.shelfplayer.feature.browse.LocalCoverUrls
import com.example.shelfplayer.feature.home.HomeActions
import com.example.shelfplayer.feature.home.HomeScreen
import com.example.shelfplayer.feature.player.FullPlayer
import com.example.shelfplayer.feature.player.MINI_PLAYER_MIN_HEIGHT
import com.example.shelfplayer.feature.player.MiniPlayer
import com.example.shelfplayer.feature.player.PlayerActions
import com.example.shelfplayer.feature.settings.AppearanceActions
import com.example.shelfplayer.feature.settings.AppearanceUiState
import com.example.shelfplayer.feature.settings.appearanceTab
import com.example.shelfplayer.ui.glass.LocalCardHazeState
import com.example.shelfplayer.ui.glass.LocalGlassHazeState
import com.example.shelfplayer.ui.glass.LocalPlayerChromeBottomInset
import com.example.shelfplayer.ui.glass.appBackdrop
import com.example.shelfplayer.ui.visualStyle
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

@Preview(name = "Home", showSystemUi = true)
@Preview(name = "Home, dark and large text", showSystemUi = true, uiMode = UI_MODE_NIGHT_YES, fontScale = 1.5f)
@Composable
fun HomeDesignPreview() = HomePreview(BookWaveDesign.Classic)

@Preview(name = "Home, Minimal", showSystemUi = true)
@Composable
fun MinimalHomeDesignPreview() = HomePreview(BookWaveDesign.Minimal)

@Preview(name = "Home, Editorial", showSystemUi = true)
@Composable
fun EditorialHomeDesignPreview() = HomePreview(BookWaveDesign.Editorial)

@Composable
private fun HomePreview(design: BookWaveDesign) {
    PreviewTheme(design = design) {
        val cover = previewCoverUri()
        val backdropHaze = rememberHazeState()
        val contentHaze = rememberHazeState()
        var chromeInset by remember { mutableStateOf(MINI_PLAYER_MIN_HEIGHT) }
        var home by remember { mutableStateOf(DesignPreviewFixtures.home) }
        var playback by remember(cover) { mutableStateOf(DesignPreviewFixtures.playback.copy(artworkUri = cover)) }
        var expanded by remember { mutableStateOf(false) }
        val togglePlayback = { playback = playback.copy(isPlaying = !playback.isPlaying) }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().appBackdrop(flat = false).hazeSource(backdropHaze))
            CompositionLocalProvider(
                LocalCoverUrls provides CoverUrls { book -> cover.takeIf { book.coverPath != null } },
                LocalCardHazeState provides backdropHaze,
                LocalGlassHazeState provides contentHaze,
                LocalPlayerChromeBottomInset provides chromeInset,
            ) {
                HomeScreen(
                    uiState = home,
                    actions = previewHomeActions.copy(
                        onAxisChanged = { home = home.copy(axis = it) },
                        onBooksViewChanged = { home = home.copy(booksView = it) },
                        onBookPlaySelected = { togglePlayback() },
                    ),
                    modifier = Modifier.fillMaxSize().hazeSource(contentHaze),
                )
                MiniPlayer(
                    state = playback,
                    timer = SleepTimerState.Idle,
                    onTogglePlayPause = togglePlayback,
                    onOpenSleepTimer = {},
                    onExpand = { expanded = true },
                    onHeightMeasured = { chromeInset = it },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
                if (expanded) {
                    FullPlayer(
                        state = playback,
                        timer = SleepTimerState.Idle,
                        actions = previewPlayerActions.copy(
                            onTogglePlayPause = togglePlayback,
                            onSeekTo = { playback = playback.copy(position = it) },
                            onCollapse = { expanded = false },
                        ),
                    )
                }
            }
        }
    }
}

@Preview(name = "Now Playing", showSystemUi = true)
@Preview(name = "Now Playing, dark and large text", showSystemUi = true, uiMode = UI_MODE_NIGHT_YES, fontScale = 1.5f)
@Composable
fun PlayerDesignPreview() = PlayerPreview(BookWaveDesign.Classic)

@Preview(name = "Now Playing, Minimal", showSystemUi = true)
@Preview(
    name = "Now Playing, Minimal and large text",
    uiMode = UI_MODE_NIGHT_YES,
    fontScale = 1.5f,
    showSystemUi = true,
)
@Composable
fun MinimalPlayerDesignPreview() = PlayerPreview(BookWaveDesign.Minimal)

@Preview(name = "Now Playing, Editorial", showSystemUi = true)
@Composable
fun EditorialPlayerDesignPreview() = PlayerPreview(BookWaveDesign.Editorial)

@Composable
private fun PlayerPreview(design: BookWaveDesign) {
    PreviewTheme(design = design) {
        val cover = previewCoverUri()
        var playback by remember(cover) { mutableStateOf(DesignPreviewFixtures.playback.copy(artworkUri = cover)) }
        FullPlayer(
            state = playback,
            timer = SleepTimerState.Idle,
            actions = previewPlayerActions.copy(
                onTogglePlayPause = { playback = playback.copy(isPlaying = !playback.isPlaying) },
                onSeekTo = { playback = playback.copy(position = it) },
            ),
            backEnabled = false,
        )
    }
}

@Preview(name = "Design selector", showSystemUi = true)
@Composable
fun AppearanceDesignPreview() {
    var design by remember { mutableStateOf(AppDesign.Classic) }
    PreviewTheme(design = design.visualStyle()) {
        LazyColumn(modifier = Modifier.appBackdrop(flat = true).safeDrawingPadding()) {
            appearanceTab(
                state = AppearanceUiState(design = design),
                actions = AppearanceActions(onDesignChanged = { design = it }),
            )
        }
    }
}

@Composable
internal fun PreviewTheme(design: BookWaveDesign, content: @Composable () -> Unit) {
    val window = LocalActivity.current?.window
    val dark = isSystemInDarkTheme()
    SideEffect {
        if (window != null) {
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !dark
        }
    }
    ShelfPlayerTheme(darkTheme = dark, design = design, content = content)
}

@Composable
private fun previewCoverUri(): String =
    "android.resource://${LocalContext.current.packageName}/${R.drawable.design_preview_cover}"

private val previewHomeActions = HomeActions(
    onBookSelected = {},
    onBookPlaySelected = {},
    onSeriesSelected = {},
    onAuthorSelected = {},
    onGroupSelected = {},
    onGenreEditRequested = {},
    onGenreEditReplacementChanged = {},
    onGenreEditConfirmed = {},
    onGenreEditDismissed = {},
    onQueryChanged = {},
    onSearchToggled = {},
    onAxisChanged = {},
    onBooksViewChanged = {},
    onOrderChanged = {},
    onFilterChanged = {},
    onFocusCleared = {},
    onRefresh = {},
    onProfilesSelected = {},
    onDownloadsSelected = {},
    onLoopboundSelected = {},
    onSettingsSelected = {},
    onSignInSelected = {},
)

private val previewPlayerActions = PlayerActions(
    onTogglePlayPause = {},
    onSeekTo = {},
    onOpenSpeed = {},
    onOpenSleepTimer = {},
    onOpenChapters = {},
    onCollapse = {},
)
