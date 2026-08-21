package com.example.shelfplayer.core.designsystem.layout

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * PRODUCT_SPEC 4 / §129 / §51 — the window this app is drawing into, as one of three widths.
 *
 * ### Why `LocalWindowInfo` and not the activity, and not the configuration
 *
 * `calculateWindowSizeClass(activity)` is the API most examples reach for, and it needs an `Activity`.
 * This app has exactly one, so that would work in the app and fail everywhere else: a `@Preview` has no
 * activity, and the Robolectric tier renders through `createComposeRule`, where reaching for one would
 * couple every layout test to a launcher intent.
 *
 * `Configuration.screenWidthDp` was the next attempt and Android Lint rejected it, correctly: it carries
 * different inset behaviour depending on the target SDK and is rounded to whole dp, so it is *near* the
 * window size rather than equal to it.
 *
 * `LocalWindowInfo.current.containerSize` is the window, in pixels, with no rounding and no inset
 * ambiguity — and it is still activity-free, so a `@Preview` and a Robolectric render both get a real
 * answer. A test can ask for a tablet with `qualifiers = "w840dp-h1024dp"` and read it back here.
 *
 * The breakpoints are Material 3's own (600dp, 840dp), taken from [WindowSizeClass] rather than written
 * down here, so they move when the guidance does.
 *
 * ### Why width only
 *
 * Because height decides almost nothing here. Every screen is a vertical scroll, so a short window shows
 * fewer rows of one — which needs no code. Width decides whether a second pane fits, and that is the only
 * structural question this app has. Height is still available from [windowSizeClass] for the first screen
 * that needs it.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun windowSizeClass(): WindowSizeClass {
    val size = windowSize()
    return remember(size) { WindowSizeClass.calculateFromSize(size) }
}

/** The window's own size, in dp. The one measurement everything else here is derived from. */
@Composable
fun windowSize(): DpSize {
    val container = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return remember(container, density) {
        with(density) { DpSize(container.width.toDp(), container.height.toDp()) }
    }
}

/** The width bucket alone, which is what almost every caller wants. */
@Composable
fun windowWidth(): WindowWidthSizeClass = windowSizeClass().widthSizeClass

/**
 * Whether a screen has room to put two panes side by side.
 *
 * `Medium` — a 600–840dp window, which is a large foldable open or one pane of a split tablet — is
 * deliberately **not** wide enough. Two panes in 600dp is two cramped panes; the honest use of that width
 * is one comfortable column with room around it, which is what [contentWidth] gives it.
 */
val WindowWidthSizeClass.hasRoomForTwoPanes: Boolean
    get() = this == WindowWidthSizeClass.Expanded

/**
 * PRODUCT_SPEC §51 — caps a single column of content so a wide window does not stretch it.
 *
 * A line of text that runs the full width of a tablet is a line nobody can read: the eye loses its place
 * on the return sweep somewhere past ninety characters. Typography guidance puts the comfortable measure
 * at 45–75, and [READING_WIDTH] is that measure at this app's body size.
 *
 * This is the whole adaptive story for every screen that is *only* a list — Settings, Downloads, the
 * profile switcher. They do not want a second pane; they want to stop being stretched.
 */
fun Modifier.contentWidth(max: Dp = READING_WIDTH): Modifier = fillMaxWidth().widthIn(max = max)

/**
 * The comfortable measure for a single column of body text.
 *
 * Not a round number chosen for tidiness: at this app's `bodyLarge` size it lands near seventy characters,
 * inside the 45–75 band that reading research and Material's own guidance agree on.
 */
val READING_WIDTH = 640.dp

/**
 * A `LazyColumn`'s content padding for a window wider than [READING_WIDTH].
 *
 * Padding rather than a width cap on each item, because a lazy list lays its items out independently:
 * capping every one of them centres each item while leaving the scrollbar, the dividers and the row
 * ripple spanning the whole window, which reads as a bug rather than as a layout. Padding moves the
 * column.
 */
@Composable
fun centredListPadding(width: WindowWidthSizeClass, bottom: Dp = 0.dp): PaddingValues {
    val available = windowSize().width
    val horizontal = if (width == WindowWidthSizeClass.Compact) {
        0.dp
    } else {
        ((available - READING_WIDTH) / 2).coerceAtLeast(0.dp)
    }
    return PaddingValues(start = horizontal, end = horizontal, bottom = bottom)
}
