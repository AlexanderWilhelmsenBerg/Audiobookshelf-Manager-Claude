package com.example.shelfplayer.feature.browse

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.ui.glass.GlassDefaults

/** Completion is semantic status, independent of decorative border preferences. */
@Composable
internal fun Modifier.completedInnerEdge(completed: Boolean): Modifier {
    if (!completed) return this
    val color = if (MaterialTheme.colorScheme.surface.luminance() < DARK_SURFACE_LUMINANCE) {
        CompletedEdgeDark
    } else {
        CompletedEdgeLight
    }
    return completedInnerEdge(color)
}

/** Owner-approved completion cue: fade inward, keeping every stroke inside the card's rounded boundary. */
private fun Modifier.completedInnerEdge(color: Color): Modifier = drawWithContent {
    drawContent()
    val cornerRadius = GlassDefaults.CardCornerRadius.toPx()
    val stepWidth = GLOW_STEP_WIDTH.toPx()
    for (step in GLOW_STEPS downTo 1) {
        val strokeWidth = stepWidth * step
        val inset = strokeWidth / 2f
        drawRoundRect(
            color = color.copy(alpha = GLOW_EDGE_ALPHA / (step * step)),
            topLeft = Offset(inset, inset),
            size = Size(size.width - strokeWidth, size.height - strokeWidth),
            cornerRadius = CornerRadius((cornerRadius - inset).coerceAtLeast(0f)),
            style = Stroke(width = strokeWidth),
        )
    }
}

private val GLOW_STEP_WIDTH = 1.dp
private const val GLOW_STEPS = 6
private const val GLOW_EDGE_ALPHA = 0.45f
private const val DARK_SURFACE_LUMINANCE = 0.5f

/** Stable green status roles, matching the app's existing light/dark reachability color family. */
private val CompletedEdgeLight = Color(0xFF15803D)
private val CompletedEdgeDark = Color(0xFF6EE7A0)
