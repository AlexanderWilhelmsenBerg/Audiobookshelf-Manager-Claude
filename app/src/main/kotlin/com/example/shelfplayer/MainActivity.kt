package com.example.shelfplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.core.designsystem.theme.ShelfPlayerTheme
import com.example.shelfplayer.navigation.ShelfDestinations
import com.example.shelfplayer.navigation.ShelfPlayerNavHost
import dagger.hilt.android.AndroidEntryPoint

/**
 * PRODUCT_SPEC 4 — adaptive, never orientation-locked.
 *
 * The activity is a thin shell: it resolves the theme and hosts the navigation graph. PRODUCT_SPEC
 * PLAY-001 requires playback to survive this activity being destroyed, so nothing that outlives a
 * screen may be owned here.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: AppViewModel = hiltViewModel()
            val appState by viewModel.state.collectAsStateWithLifecycle()
            ShelfPlayerTheme(
                darkTheme = appState.resolveDarkTheme(systemInDarkTheme = isSystemInDarkTheme()),
                dynamicColor = appState.dynamicColor,
            ) {
                // The graph is not composed until the start destination is known. Composing it early and
                // correcting it would show a flash of the wrong screen on every cold start, and would put
                // a spurious entry in the back stack.
                if (appState.isResolved) {
                    ShelfPlayerNavHost(
                        startDestination = if (appState.hasAnyProfile) {
                            ShelfDestinations.HOME
                        } else {
                            ShelfDestinations.SIGN_IN
                        },
                    )
                }
            }
        }
    }
}
