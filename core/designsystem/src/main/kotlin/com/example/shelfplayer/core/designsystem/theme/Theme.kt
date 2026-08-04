package com.example.shelfplayer.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * PRODUCT_SPEC SET-002 (Appearance/accessibility) — system/light/dark plus optional dynamic color.
 *
 * The palette is deliberately restrained: audiobook covers are the colourful element on every
 * screen, and a saturated surface competes with them. Contrast pairs come from Material 3's tonal
 * roles rather than hand-picked hex values, which is what keeps the high-contrast and large-text
 * requirements in PRODUCT_SPEC 2.10 achievable without a second palette.
 */
private val ShelfLightColors = lightColorScheme(
    primary = Color(0xFF2F5D62),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB6E5E8),
    onPrimaryContainer = Color(0xFF00201F),
    secondary = Color(0xFF6E5B3F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF8DFBA),
    onSecondaryContainer = Color(0xFF261904),
    surface = Color(0xFFFBFAF8),
    onSurface = Color(0xFF1A1C1C),
    surfaceVariant = Color(0xFFDBE4E4),
    onSurfaceVariant = Color(0xFF3F4949),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

private val ShelfDarkColors = darkColorScheme(
    primary = Color(0xFF9ACACC),
    onPrimary = Color(0xFF003735),
    primaryContainer = Color(0xFF14494C),
    onPrimaryContainer = Color(0xFFB6E5E8),
    secondary = Color(0xFFDBC3A0),
    onSecondary = Color(0xFF3D2E16),
    secondaryContainer = Color(0xFF55442A),
    onSecondaryContainer = Color(0xFFF8DFBA),
    surface = Color(0xFF101414),
    onSurface = Color(0xFFE0E3E2),
    surfaceVariant = Color(0xFF3F4949),
    onSurfaceVariant = Color(0xFFBEC9C8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/**
 * @param darkTheme resolved by the caller from the user's stored preference, so that "follow the
 *   system" is one branch of a decision made in one place instead of being re-derived per screen.
 * @param dynamicColor honoured only where the platform supports it (API 31+). PRODUCT_SPEC SET-002
 *   makes this opt-in, so the default is the ShelfPlayer palette.
 */
@Composable
fun ShelfPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && supportsDynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor && supportsDynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> ShelfDarkColors
        else -> ShelfLightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ShelfPlayerTypography,
        content = content,
    )
}
