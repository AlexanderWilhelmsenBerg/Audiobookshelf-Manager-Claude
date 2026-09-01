package com.example.shelfplayer.feature.home

import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Gives the floating library navigation a little physical weight instead of pinning it to the glass.
 *
 * A drag moves the pill only a small fraction of the finger delta. The movement is sampled before the
 * child consumes the scroll so every lazy list and scrollable home state produces the same response.
 * When input ends, the last fling velocity is carried into a damped spring back to rest.
 */
internal class HomeAxisBarMotionState(private val scope: CoroutineScope, private val maxOffsetPx: Float) {
    private val offset = Animatable(0f)
    private var motionJob: Job? = null
    private var settleJob: Job? = null

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput) nudge(available.y)
            return Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            settle(initialVelocity = (consumed.y + available.y) * FLING_VELOCITY_RESPONSE)
            return Velocity.Zero
        }
    }

    val offsetPx: Float
        get() = offset.value

    private fun nudge(scrollDelta: Float) {
        if (abs(scrollDelta) < MIN_SCROLL_DELTA_PX) return

        val target = (offset.value + scrollDelta * DRAG_RESPONSE).coerceIn(-maxOffsetPx, maxOffsetPx)
        motionJob?.cancel()
        motionJob = scope.launch { offset.snapTo(target) }

        settleJob?.cancel()
        settleJob = scope.launch {
            delay(SETTLE_DELAY_MILLIS)
            settle()
        }
    }

    private suspend fun settle(initialVelocity: Float = 0f) {
        settleJob?.cancel()
        motionJob?.cancel()
        offset.animateTo(
            targetValue = 0f,
            animationSpec = spring(
                dampingRatio = RETURN_DAMPING_RATIO,
                stiffness = RETURN_STIFFNESS,
            ),
            initialVelocity = initialVelocity,
        )
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

private val MAX_AXIS_BAR_OFFSET = 8.dp
private const val DRAG_RESPONSE = 0.22f
private const val FLING_VELOCITY_RESPONSE = 0.025f
private const val MIN_SCROLL_DELTA_PX = 0.25f
private const val SETTLE_DELAY_MILLIS = 120L
private const val RETURN_DAMPING_RATIO = 0.72f
private const val RETURN_STIFFNESS = 170f
