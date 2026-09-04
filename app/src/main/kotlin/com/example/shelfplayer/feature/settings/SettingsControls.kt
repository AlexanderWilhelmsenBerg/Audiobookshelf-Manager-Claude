package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The two controls every settings section is built from.
 *
 * Shared rather than private to a tab because they had been written three times by the fourth section that
 * needed them, and three copies of a toggle is three places for its accessibility semantics to drift. The
 * `Role` and the `onCheckedChange = null` below are the part that matters and the part a copy loses.
 */

/** A row of mutually exclusive chips — a radio group that fits on one line. */
@Composable
internal fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelected(option) },
                label = { Text(text = label(option)) },
                modifier = Modifier.selectable(
                    selected = option == selected,
                    role = Role.RadioButton,
                    onClick = { onSelected(option) },
                ),
            )
        }
    }
}

/** A labelled switch whose whole row is the target. */
@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        // `null` rather than a second handler: the whole row is the toggle, and a switch with its own click
        // target inside a toggleable row is two controls a screen reader has to describe.
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * A labelled slider whose current value is shown as words beside its name.
 *
 * ### Why the value is in the label rather than under the thumb
 *
 * Because a thumb has no room for it and a number that moves with the thumb is a number nobody can read
 * while dragging. Putting it in the row's own label means it is in the same place before, during and
 * after the gesture — and it is what a screen reader announces, since [Slider] itself can only offer a
 * percentage of its range.
 *
 * `steps` is the count *between* the ends, so a 0..48 range in whole dp is 47 of them. Getting that off
 * by one gives a slider that cannot reach one end, which is the sort of thing only a device shows.
 */
@Composable
internal fun SliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}
