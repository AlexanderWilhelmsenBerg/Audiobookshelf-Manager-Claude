package com.example.shelfplayer.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.playback.SleepTimerState
import com.example.shelfplayer.playback.PlaybackUiState
import com.example.shelfplayer.ui.glass.LocalGlassHazeState
import com.example.shelfplayer.ui.glass.systemGlass
import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-001 — what is playing, on every screen.
 *
 * ### Why it renders nothing when nothing is playing
 *
 * A persistent empty bar costs a strip of every screen to say "no". It appears when there is a book and
 * disappears when the user stops one, which also means no screen has to reserve space for it.
 *
 * ### The accessibility bits are not decoration
 *
 * The transport button's content description changes with the state, because "Play" on a button that
 * pauses is what a screen-reader user hears instead of what happens. The title is a polite live region
 * so that a book starting is announced without interrupting whatever is being read.
 */
@Composable
fun MiniPlayer(
    state: PlaybackUiState,
    timer: SleepTimerState,
    onTogglePlayPause: () -> Unit,
    onStop: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onExpand: () -> Unit,
    skips: SkipControls,
    modifier: Modifier = Modifier,
    /**
     * Called with how much of the window the bar covers, **excluding** the system navigation bar.
     *
     * That exclusion is the contract `playerChromeClearance` documents: a screen with a `Scaffold`
     * already receives the navigation bar in its own inner padding. Reporting the raw surface height
     * instead gave six screens a gap the size of the gesture handle.
     *
     * Defaulted to a no-op so the screen tests and the accessibility net render it unchanged; the one
     * caller that needs it is `MainActivity`, which turns it into the inset every screen reads.
     */
    onHeightMeasured: (Dp) -> Unit = {},
) {
    val activeTimerLabel = stringResource(R.string.sleep_timer_active, timer.remaining.asShortLabel())
    val openLabel = stringResource(R.string.player_open)
    val backSeconds = skips.intervals.back.inWholeSeconds.toInt()
    val forwardSeconds = skips.intervals.forward.inWholeSeconds.toInt()
    val hazeState = LocalGlassHazeState.current
    val systemBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val density = LocalDensity.current
    AnimatedVisibility(
        visible = state.bookId != null,
        modifier = modifier.fillMaxWidth(),
        enter = expandVertically(
            expandFrom = Alignment.Bottom,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        ),
        exit = shrinkVertically(
            shrinkTowards = Alignment.Bottom,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                // The glass runs to the bottom of the window and the *content* is inset instead. Stopping
                // the surface above the system navigation bar would leave a strip of unblurred shelf under
                // it; stopping the content there is what keeps the controls off the gesture handle.
                // A floor, not a fixed height — but a shallow one. Measured, the bar comes out at 72dp,
                // 70dp and 74dp at font scales 1.0, 1.3 and 2.0: the content wants a few dp more than the
                // floor and never much more, because the title and author are one line each whatever the
                // scale. The floor is what the owner asked to keep unchanged; the handful of dp above it
                // is the content asking, and a bar that refused would clip the author's descenders.
                .heightIn(min = MINI_PLAYER_MIN_HEIGHT + systemBarInset)
                // What the bar actually took, reported upward so every screen under it can scroll
                // clear. Nothing measures an overlay the way `Scaffold` measures a bottom bar, so the
                // bar has to say.
                //
                // **Minus the system navigation bar**, and that subtraction is the whole contract.
                // `playerChromeClearance` says so in as many words: a screen with a `Scaffold` already
                // receives the navigation bar in its own inner padding, and adding it twice is a gap the
                // size of the gesture handle. The first version of this reported the surface's raw height
                // — which includes the inset, because the glass deliberately runs to the bottom of the
                // window — so every one of the six screens that reads this gained that gap. Reported from
                // a device as *"it is taller"*, and constant with font scale, which is what named it.
                .onSizeChanged { size ->
                    onHeightMeasured(playerClearanceOf(with(density) { size.height.toDp() }, systemBarInset))
                }
                .testTag(MINI_PLAYER_TEST_TAG)
                .systemGlass(
                    state = hazeState,
                    backgroundColor = MaterialTheme.colorScheme.surface,
                    shape = RectangleShape,
                ),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Box(
                // `fillMaxWidth`, never `fillMaxSize`. The surface above has a height *floor* rather than a
                // fixed height, so its incoming maximum is whatever it was placed in — the whole window, in
                // `MainActivity`'s overlay `Box`. A child that fills the maximum therefore made the bar the
                // height of the screen: a full-window frosted surface over the app, and `onHeightMeasured`
                // reporting the window height as the player's inset to every screen. Wrapping is what makes
                // the floor a floor. `Surface` propagates its minimum constraints, so this is still 68dp
                // tall when the content is shorter than that.
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = systemBarInset),
                // Defensive rather than load-bearing: at every font scale the content is taller than the
                // 68dp floor, so the floor does not currently decide the height. If it ever does — a
                // shorter bar, a taller system inset — a wrapping child of a `Box` is placed at the top
                // unless something says otherwise.
                contentAlignment = Alignment.CenterStart,
            ) {
                // `IntrinsicSize.Min` is what actually bounds this bar, and it is not decoration. The tap
                // region below still uses `fillMaxHeight` so that the whole visible row opens the player —
                // and a child that fills the height *participates in measuring* its parent, resolving
                // against the incoming maximum. With a height floor above instead of a fixed height, that
                // maximum is the window. Asking the row for its intrinsic height replaces the maximum with
                // the content's own before anything fills it.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The cover and the text are one tap target that opens the player; the controls sit
                    // outside it. That is "anywhere except the buttons", expressed as a region rather than as
                    // a click on the whole bar that the buttons then have to out-compete.
                    //
                    // Not `clickable` on the `Surface`: a clickable container **merges** its descendants'
                    // semantics, so the buttons inside would stop being separate nodes — a screen reader
                    // would find one control where there are five, and a tap on the pause icon would fire
                    // the container's click instead of the button's.
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(onClick = onExpand, onClickLabel = openLabel),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MiniPlayerArtwork(state = state, isLoading = state.isLoading)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                        ) {
                            /*
                             * PRODUCT_SPEC PLAY-001 / SET-002 — long titles **scroll** rather than being
                             * cut off.
                             *
                             * The bar has one line for the title and one for the author, and at a large
                             * font scale a real title runs out of room in both. Ellipsis was the previous
                             * answer and a device found it wanting: *"the text was cut off"*. It is the
                             * bar's whole job to say what is playing, and half a title does not.
                             *
                             * `basicMarquee` and **not** a taller bar. Growing the bar to fit the text
                             * would take a strip of every screen from every listener to serve the longest
                             * title, and the owner asked for the minimum height to stay where it was.
                             * Text that fits does not move — the marquee only animates when the content
                             * is wider than the box — so nothing changes for a short title.
                             *
                             * `Clip` rather than `Ellipsis`: an ellipsis truncates the string *before*
                             * the marquee gets a chance to scroll it, so the two together produce a
                             * scrolling ellipsis and no more text than before.
                             */
                            Text(
                                text = state.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                modifier = Modifier
                                    .semantics { liveRegion = LiveRegionMode.Polite }
                                    .basicMarquee(iterations = Int.MAX_VALUE),
                            )
                            state.author?.let { author ->
                                Text(
                                    text = author,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Clip,
                                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                                )
                            }
                        }
                    }
                    // PRODUCT_SPEC PLAY-007 — the two skips a listener reaches for without looking, at the
                    // configured intervals. The glyph drops its number rather than printing a wrong one.
                    MiniPlayerButton(onClick = skips.onBack) {
                        Icon(
                            imageVector = SkipIcons.back(skips.intervals.back),
                            contentDescription = pluralStringResource(
                                R.plurals.player_skip_back,
                                backSeconds,
                                backSeconds,
                            ),
                            modifier = Modifier.size(TRANSPORT_ICON_SIZE),
                        )
                    }
                    // PRODUCT_SPEC PLAY-008 — the remaining time doubles as the control's label, so a
                    // listener can see the timer is running without opening anything.
                    MiniPlayerButton(onClick = onOpenSleepTimer) {
                        if (timer.isActive) {
                            Text(
                                text = timer.remaining.asCountdownLabel(),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.semantics {
                                    contentDescription = activeTimerLabel
                                },
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Bedtime,
                                contentDescription = stringResource(R.string.sleep_timer_open),
                                modifier = Modifier.size(TRANSPORT_ICON_SIZE),
                            )
                        }
                    }
                    MiniPlayerButton(onClick = onTogglePlayPause) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(
                                if (state.isPlaying) R.string.player_pause else R.string.player_resume,
                            ),
                            modifier = Modifier.size(PLAY_ICON_SIZE),
                        )
                    }
                    MiniPlayerButton(onClick = skips.onForward) {
                        Icon(
                            imageVector = SkipIcons.forward(skips.intervals.forward),
                            contentDescription = pluralStringResource(
                                R.plurals.player_skip_forward,
                                forwardSeconds,
                                forwardSeconds,
                            ),
                            modifier = Modifier.size(TRANSPORT_ICON_SIZE),
                        )
                    }
                    MiniPlayerButton(onClick = onStop) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.player_stop),
                            modifier = Modifier.size(TRANSPORT_ICON_SIZE),
                        )
                    }
                }
                LinearProgressIndicator(
                    progress = { state.fractionComplete },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(PROGRESS_HEIGHT),
                )
                // Always drawn. A previous version hid these above a font scale of 1.3, on the grounds
                // that they shared the top strip with the title and a clock nobody can read is worth less
                // than the title it sits on. The device disagreed — *"the progress timers went away"* —
                // and the premise was wrong anyway: the bar measures 72dp, 70dp and 74dp at scales 1.0,
                // 1.3 and 2.0, so there was never the dramatic growth the threshold was protecting.
                // Long text is made readable by scrolling it instead; see the title and author above.
                MiniPlayerTimeLabels(
                    state = state,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(
                            start = ARTWORK_SIZE + TIME_LABEL_HORIZONTAL_INSET,
                            top = TIME_LABEL_TOP_INSET,
                            end = TIME_LABEL_HORIZONTAL_INSET,
                        ),
                )
            }
        }
    }
}

@Composable
private fun MiniPlayerTimeLabels(state: PlaybackUiState, modifier: Modifier = Modifier) {
    val elapsed = state.position.coerceAtLeast(Duration.ZERO)
    val remaining = (state.duration - elapsed).coerceAtLeast(Duration.ZERO)
    Box(modifier = modifier) {
        Text(
            text = elapsed.asChapterClock(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.align(Alignment.TopStart),
        )
        Text(
            text = "-${remaining.asChapterClock()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

@Composable
private fun MiniPlayerButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            // Was `fillMaxHeight` against a fixed bar. The bar's height is now the content's, so a control
            // that filled it would grow with the text it sits beside and push the bar taller still.
            .size(CONTROL_SIZE),
    ) {
        content()
    }
}

/** The cover touches the left, top and bottom edges of the mini player with no surrounding padding. */
@Composable
private fun MiniPlayerArtwork(state: PlaybackUiState, isLoading: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(ARTWORK_SIZE)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = GLASS_ARTWORK_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        val uri = state.artworkUri
        if (uri == null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        } else {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

/**
 * PRODUCT_SPEC PLAY-001 — how much of a screen the bar covers, from how tall it measured.
 *
 * ### The subtraction is the whole contract, and it was missing
 *
 * `playerChromeClearance` documents it in as many words: *"The system navigation bar is deliberately not
 * included. A screen with a `Scaffold` already receives that in its own inner padding, and adding it twice
 * is a gap the size of the gesture handle."* Six screens read that value.
 *
 * The bar's own surface **does** include the navigation bar, deliberately — the glass runs to the bottom
 * of the window so there is no strip of unblurred content beneath it, and only the *content* is inset. So
 * the measured height and the number the screens want differ by exactly that inset, and the first version
 * of this reported the measurement raw. Every screen then reserved the gesture handle twice. Reported from
 * a device as *"it is taller"* — and constant with font scale, which is what separated it from the bar's
 * own growth.
 *
 * A function rather than two lines inside `onSizeChanged` because it is the part with a rule in it, and a
 * rule that only exists inside a lambda is a rule no test can reach.
 *
 * Clamped at zero: a measurement smaller than the inset is not a negative clearance, it is a bar that has
 * not been laid out yet.
 */
internal fun playerClearanceOf(measuredHeight: Dp, systemBarInset: Dp): Dp =
    (measuredHeight - systemBarInset).coerceAtLeast(0.dp)

/** The bar itself, so a test can ask whether it filled the window it floats in. */
internal const val MINI_PLAYER_TEST_TAG = "mini-player-bar"

/**
 * The bar's floor, and what `MainActivity` assumes until the bar has measured itself once.
 *
 * Roughly 60% of the 112dp this bar used to be. Renamed from `MINI_PLAYER_HEIGHT` because it is no longer
 * *the* height: at a large font scale the bar is taller, and the number every other screen reads comes
 * from `onHeightMeasured` rather than from here.
 */
internal val MINI_PLAYER_MIN_HEIGHT = 68.dp

/** Square, both of them, now that the bar's height is its content's and nothing may fill it. */
private val ARTWORK_SIZE = 46.dp
private val CONTROL_SIZE = 48.dp
private val TRANSPORT_ICON_SIZE = 28.dp
private val PLAY_ICON_SIZE = 34.dp
private val PROGRESS_HEIGHT = 2.dp
private val TIME_LABEL_HORIZONTAL_INSET = 6.dp
private val TIME_LABEL_TOP_INSET = 3.dp
private const val GLASS_ARTWORK_ALPHA = 0.72f
