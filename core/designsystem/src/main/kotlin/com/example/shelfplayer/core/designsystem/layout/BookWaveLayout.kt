package com.example.shelfplayer.core.designsystem.layout

import androidx.compose.ui.unit.dp

/** Layout dimensions for BookWave's cover-led shelves and playback surfaces. */
object BookWaveLayout {
    val ShelfGutter = 16.dp
    val ShelfCardGap = 12.dp
    val ShelfCardWidth = 160.dp
    val MinimalShelfCardWidth = 112.dp

    /** The card and its cover share this corner. */
    val ShelfCardCorner = 8.dp

    val PlayerGutter = 24.dp

    /** Prevent a full-width square cover from dominating a tablet layout. */
    val PlayerArtworkMax = 420.dp
    val MinimalPlayerArtworkMax = 264.dp
    val MiniPlayerArtwork = 46.dp
}
