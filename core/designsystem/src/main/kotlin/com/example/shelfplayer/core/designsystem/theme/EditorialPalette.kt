package com.example.shelfplayer.core.designsystem.theme

import androidx.compose.ui.graphics.Color

private val Paper = Color(0xFFF8F3EB)
private val PaperInk = Color(0xFF24211D)
private val Charcoal = Color(0xFF1C1917)
private val LightInk = Color(0xFFF4EBE0)

internal val EditorialLightColors = MinimalLightColors.copy(
    primary = Color(0xFF783D29),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF3DBCB),
    onPrimaryContainer = Color(0xFF492419),
    secondary = Color(0xFF65564A),
    secondaryContainer = Color(0xFFEAE1D5),
    onSecondaryContainer = Color(0xFF342820),
    background = Paper,
    onBackground = PaperInk,
    surface = Paper,
    onSurface = PaperInk,
    surfaceVariant = Color(0xFFECE3D8),
    onSurfaceVariant = Color(0xFF51463D),
    surfaceContainerLowest = Color(0xFFFFFAF2),
    surfaceContainerLow = Color(0xFFF4EEE5),
    surfaceContainer = Color(0xFFF0E9DF),
    surfaceContainerHigh = Color(0xFFECE4D9),
    surfaceContainerHighest = Color(0xFFE7DED2),
    outlineVariant = Color(0xFFC5B8A9),
)

internal val EditorialDarkColors = MinimalDarkColors.copy(
    primary = Color(0xFFF3B89E),
    onPrimary = Color(0xFF3C2015),
    primaryContainer = Color(0xFF57372B),
    onPrimaryContainer = Color(0xFFFFDFCE),
    secondary = Color(0xFFDBBDA7),
    secondaryContainer = Color(0xFF483C33),
    onSecondaryContainer = Color(0xFFF1E3D6),
    background = Charcoal,
    onBackground = LightInk,
    surface = Charcoal,
    onSurface = LightInk,
    surfaceVariant = Color(0xFF38302A),
    onSurfaceVariant = Color(0xFFD2C3B7),
    surfaceContainerLowest = Color(0xFF161310),
    surfaceContainerLow = Color(0xFF211D19),
    surfaceContainer = Color(0xFF28221D),
    surfaceContainerHigh = Color(0xFF302922),
    surfaceContainerHighest = Color(0xFF383027),
    outlineVariant = Color(0xFF5C5046),
)
