package com.example.shelfplayer.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.example.shelfplayer.R

/**
 * The infrequent app-level destinations from Home.
 *
 * Search, view mode and refresh remain direct actions because they operate on the shelf currently on
 * screen. Profiles, downloads, the bundled game and Settings change place instead, so keeping four
 * separate 48 dp buttons beside the title spends scarce phone width on actions used much less often.
 */
@Composable
internal fun HomeOverflowMenu(actions: HomeActions) {
    var expanded by remember { mutableStateOf(false) }

    fun select(action: () -> Unit) {
        expanded = false
        action()
    }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.home_more),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.home_profiles)) },
                onClick = { select(actions.onProfilesSelected) },
                leadingIcon = {
                    Icon(imageVector = Icons.Filled.AccountCircle, contentDescription = null)
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.home_downloads)) },
                onClick = { select(actions.onDownloadsSelected) },
                leadingIcon = {
                    Icon(imageVector = Icons.Filled.Download, contentDescription = null)
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.home_loopbound)) },
                onClick = { select(actions.onLoopboundSelected) },
                leadingIcon = {
                    Icon(imageVector = Icons.Filled.SportsEsports, contentDescription = null)
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.home_settings)) },
                onClick = { select(actions.onSettingsSelected) },
                leadingIcon = {
                    Icon(imageVector = Icons.Filled.Settings, contentDescription = null)
                },
            )
        }
    }
}
