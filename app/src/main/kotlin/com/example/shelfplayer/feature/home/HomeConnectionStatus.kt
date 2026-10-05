package com.example.shelfplayer.feature.home

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.ServerStatus

/** LIB-002/spec21: sighted users can distinguish states without color; offline is not server failure. */
@Composable
internal fun ServerStatusIndicator(status: ServerStatus, isOffline: Boolean, modifier: Modifier = Modifier) {
    val symbol = when {
        isOffline -> Icons.Filled.CloudOff
        status == ServerStatus.Reachable -> Icons.Filled.CheckCircle
        status == ServerStatus.Unreachable -> Icons.Outlined.ErrorOutline
        else -> Icons.AutoMirrored.Outlined.HelpOutline
    }
    val description = stringResource(
        when {
            isOffline -> R.string.home_server_offline
            status == ServerStatus.Reachable -> R.string.home_server_reachable
            status == ServerStatus.Unreachable -> R.string.home_server_unreachable
            else -> R.string.home_server_unknown
        },
    )
    Icon(
        imageVector = symbol,
        contentDescription = description,
        tint = homeStatusTint(
            status = status,
            isOffline = isOffline,
            background = MaterialTheme.colorScheme.surface.compositeOver(MaterialTheme.colorScheme.background),
            neutral = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = modifier.size(20.dp),
    )
}

/** Select semantic green/red against the resolved app surface over its ground, independent of the system theme. */
internal fun homeStatusTint(status: ServerStatus, isOffline: Boolean, background: Color, neutral: Color): Color {
    val candidates = when {
        isOffline || status == ServerStatus.Unknown -> listOf(neutral)
        status == ServerStatus.Reachable -> listOf(ReachableDark, ReachableLight)
        else -> listOf(UnreachableDark, UnreachableLight)
    }
    val semantic = candidates.maxBy { statusContrast(it, background) }
    // Custom surfaces can defeat both semantic shades. Preserve the shape and use legible ink.
    return if (statusContrast(semantic, background) >= MIN_STATUS_CONTRAST) {
        semantic
    } else {
        listOf(Color.Black, Color.White).maxBy { statusContrast(it, background) }
    }
}

private fun statusContrast(foreground: Color, background: Color): Float {
    val first = foreground.luminance()
    val second = background.luminance()
    return (maxOf(first, second) + CONTRAST_OFFSET) / (minOf(first, second) + CONTRAST_OFFSET)
}

private const val MIN_STATUS_CONTRAST = 3f
private const val CONTRAST_OFFSET = 0.05f
private val ReachableDark = Color(0xFF6EE7A0)
private val ReachableLight = Color(0xFF15803D)
private val UnreachableDark = Color(0xFFFF8A80)
private val UnreachableLight = Color(0xFFC62828)
