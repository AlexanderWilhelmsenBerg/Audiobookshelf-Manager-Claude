package com.example.shelfplayer.feature.settings

import androidx.compose.ui.graphics.Color
import com.example.shelfplayer.core.model.settings.GlassTint

/**
 * Resolve a glass tint against the accent the theme actually painted.
 *
 * [GlassTint.FollowAccent] is deliberately different from an accent-pinned tint: it follows the current
 * Material theme primary, which may come from Material You rather than from the stored accent preference.
 * Fixed and pinned tints keep their own colour and ignore [activeAccent].
 */
internal fun GlassTint.resolvedColor(activeAccent: Color, storedAccentArgb: Long): Color =
    if (this == GlassTint.FollowAccent) activeAccent else Color(argbOr(storedAccentArgb))
