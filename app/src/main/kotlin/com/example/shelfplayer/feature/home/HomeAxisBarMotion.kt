package com.example.shelfplayer.feature.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Gives the floating library navigation a little physical weight instead of pinning it to the glass.
 *
 * Scroll deltas only nudge the pill a fraction of the finger movement and are capped at a small visual
 * displacement. Once scrolling goes quiet, a soft spring carries the pill back to its resting place.
 */
internal class HomeAxisBarMotionState(
    private val scope: CoroutineScope,
    private val maxOffsetPx: Float,
) {
    private val offset = Animatable(0f)
    private var motionJob: Job? = null
    private var settleJob: Job? = null

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            nudge(consumed.y)
            return Offset.Zero
        }
    }

    val offsetPx: Float
        get() = offset.value

    private fun nudge(scrollDelta: Float) {
        if (abs(scrollDelta) < MIN_SCROLL_DELTA_PX) return

        val target = (offset.value + scrollDelta * SCROLL_RESPONSE).coerceIn(-maxOffsetPx, maxOffsetPx)
        motionJob?.cancel()
        motionJob = scope.launch {
            offset.animateTo(
                targetValue = target,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
            )
        }

        settleJob?.cancel()
        settleJob = scope.launch {
            delay(SETTLE_DELAY_MILLIS)
            motionJob?.cancel()
            offset.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
            )
        }
    }
}

@Composable
internal fun rememberHomeAxisBarMotion(): HomeAxisBarMotionState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    return remember(scope, density) {
        HomeAxisBarMotionState(
            scope = scope,
            maxOffsetPx = with(density) { MAX_AXIS_BAR_OFFSET.toPx() },
        )
    }
}

internal fun Modifier.captureHomeAxisBarMotion(state: HomeAxisBarMotionState): Modifier = nestedScroll(state.connection)

internal fun Modifier.followHomeAxisBarMotion(state: HomeAxisBarMotionState): Modifier = graphicsLayer {
    translationY = state.offsetPx
}

private val MAX_AXIS_BAR_OFFSET = 10.dp
private const val SCROLL_RESPONSE = 0.08f
private const val MIN_SCROLL_DELTA_PX = 0.5f
private const val SETTLE_DELAY_MILLIS = 90L
