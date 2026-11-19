package com.example.shelfplayer.core.designsystem.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape

/** Presentation styles. The app maps its persisted SET-002 choice onto these tokens. */
enum class BookWaveDesign(val usesGlass: Boolean, val sharpEdges: Boolean) {
    Classic(usesGlass = true, sharpEdges = false),
    Minimal(usesGlass = false, sharpEdges = true),
    ;

    fun surfaceShape(rounded: Shape): Shape = if (sharpEdges) RectangleShape else rounded
}

val LocalBookWaveDesign = staticCompositionLocalOf { BookWaveDesign.Classic }
