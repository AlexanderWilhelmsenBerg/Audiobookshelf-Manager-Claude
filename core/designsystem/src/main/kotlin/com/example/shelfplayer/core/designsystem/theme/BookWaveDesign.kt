package com.example.shelfplayer.core.designsystem.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/** Presentation styles. The app maps its persisted SET-002 choice onto these tokens. */
enum class BookWaveDesign(
    val usesGlass: Boolean,
    val sharpEdges: Boolean,
    val featuredResume: Boolean,
    val compactPlayer: Boolean,
) {
    Classic(usesGlass = true, sharpEdges = false, featuredResume = false, compactPlayer = false),
    Minimal(usesGlass = false, sharpEdges = true, featuredResume = true, compactPlayer = true),
    Editorial(usesGlass = false, sharpEdges = true, featuredResume = true, compactPlayer = true),
    ;

    fun surfaceShape(rounded: Shape): Shape = if (sharpEdges) RectangleShape else rounded

    fun bookTitle(style: TextStyle): TextStyle = when (this) {
        Editorial -> style.copy(fontFamily = FontFamily.Serif)
        Classic, Minimal -> style
    }
}

val LocalBookWaveDesign = staticCompositionLocalOf { BookWaveDesign.Classic }
