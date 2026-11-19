package com.example.shelfplayer.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val SharpCorner = RoundedCornerShape(2.dp)

internal val MinimalShapes = Shapes(
    extraSmall = SharpCorner,
    small = SharpCorner,
    medium = SharpCorner,
    large = SharpCorner,
    extraLarge = SharpCorner,
)

internal val MinimalLightColors = lightColorScheme(
    primary = Color(0xFF006458),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5EAE5),
    onPrimaryContainer = Color(0xFF00372F),
    secondary = Color(0xFF455A55),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE7E3),
    onSecondaryContainer = Color(0xFF182C27),
    background = Color(0xFFF6F7F5),
    onBackground = Color(0xFF151918),
    surface = Color(0xFFF6F7F5),
    onSurface = Color(0xFF151918),
    surfaceVariant = Color(0xFFE2E8E5),
    onSurfaceVariant = Color(0xFF404B47),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F3F0),
    surfaceContainer = Color(0xFFEDF0ED),
    surfaceContainerHigh = Color(0xFFE8ECE9),
    surfaceContainerHighest = Color(0xFFDFE5E1),
    outlineVariant = Color(0xFFB9C6C0),
)

internal val MinimalDarkColors = darkColorScheme(
    primary = Color(0xFF8AD5C6),
    onPrimary = Color(0xFF00372F),
    primaryContainer = Color(0xFF204B43),
    onPrimaryContainer = Color(0xFFE2F5EF),
    secondary = Color(0xFFB2C6BE),
    onSecondary = Color(0xFF21352F),
    secondaryContainer = Color(0xFF30433E),
    onSecondaryContainer = Color(0xFFE1EEE8),
    background = Color(0xFF141918),
    onBackground = Color(0xFFEBF0ED),
    surface = Color(0xFF141918),
    onSurface = Color(0xFFEBF0ED),
    surfaceVariant = Color(0xFF2C3733),
    onSurfaceVariant = Color(0xFFBCCBC4),
    surfaceContainerLowest = Color(0xFF0F1413),
    surfaceContainerLow = Color(0xFF181E1C),
    surfaceContainer = Color(0xFF1C2421),
    surfaceContainerHigh = Color(0xFF212927),
    surfaceContainerHighest = Color(0xFF293330),
    outlineVariant = Color(0xFF465550),
)
