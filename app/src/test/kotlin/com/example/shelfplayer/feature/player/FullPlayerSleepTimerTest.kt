package com.example.shelfplayer.feature.player

/**
 * BW-SLEEP-01 full-player timer projection is exercised by
 * `PlayerAccessibilityScreenTest`, the repository's established Robolectric FullPlayer harness.
 *
 * Keeping that coverage in the shared harness avoids a second Compose instrumentation owner for the same
 * screen while still asserting the production countdown text, accessibility label and existing tap action.
 */
