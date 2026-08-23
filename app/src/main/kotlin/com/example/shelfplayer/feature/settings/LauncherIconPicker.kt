package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.shelfplayer.launcher.LauncherIcon

/**
 * PRODUCT_SPEC SET-003 — the launcher icons, as a horizontally scrollable row of things to tap.
 *
 * ### Swatches rather than a list of names
 *
 * The choice is entirely visual: nobody picks *Illuminated* over *Vintage* by reading the words. So the
 * words are captions under the pictures and the pictures are the control, sized at 56.dp — a comfortable
 * touch target that is also close enough to a real home-screen icon to judge one by.
 *
 * ### The preview is composited, not the icon drawable
 *
 * Each swatch paints the adaptive icon's own two layers: the background colour, then the foreground
 * bitmap, clipped to a circle. Drawing `@mipmap/ic_launcher_…` instead would render an unmasked 108dp
 * square with the artwork small and floating in the middle of it, which is not what any launcher shows.
 */
@Composable
fun LauncherIconPicker(selected: LauncherIcon, onSelected: (LauncherIcon) -> Unit, modifier: Modifier = Modifier) {
    // Start at the selected swatch so a saved choice near the end of the row is visible on a narrow
    // phone. There are only seven fixed choices, so composing all of them in a scrollable Row is both
    // cheap and more reliable than a virtualized list whose initial index can be clamped before its item
    // provider is populated. `rememberScrollState` uses this value only when the picker is first created;
    // tapping another swatch therefore does not make the whole row jump.
    val stride = with(LocalDensity.current) { SWATCH_STRIDE.roundToPx() }
    val scrollState = rememberScrollState(initial = selected.ordinal * stride)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(PaddingValues(horizontal = 16.dp, vertical = 8.dp))
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LauncherIcon.entries.forEach { icon ->
            LauncherIconSwatch(
                icon = icon,
                isSelected = icon == selected,
                onSelected = { onSelected(icon) },
                modifier = Modifier.width(72.dp),
            )
        }
    }
}

@Composable
private fun LauncherIconSwatch(
    icon: LauncherIcon,
    isSelected: Boolean,
    onSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(icon.label)
    Column(
        modifier = modifier.selectable(
            selected = isSelected,
            role = Role.RadioButton,
            // The whole cell is the target, so the caption is as tappable as the picture. The label is
            // on the selectable rather than on the image, so TalkBack announces one thing, once.
            onClick = onSelected,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(
                modifier = Modifier
                    .size(SWATCH)
                    .clip(CircleShape)
                    .background(colorResource(icon.background)),
            ) {
                Image(
                    painter = painterResource(icon.foreground),
                    // Null: the caption below already names it, and the row's `selectable` carries the
                    // state. A description here would make TalkBack read the name twice.
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(TICK)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private val SWATCH = 56.dp
private val TICK = 20.dp
private val SWATCH_STRIDE = 84.dp
