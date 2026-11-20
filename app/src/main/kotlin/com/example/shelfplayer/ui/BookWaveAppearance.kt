package com.example.shelfplayer.ui

import com.example.shelfplayer.core.designsystem.theme.BookWaveDesign
import com.example.shelfplayer.core.model.settings.AppDesign

internal val AppDesign.usesDesignAccent: Boolean get() = this != AppDesign.Classic

internal fun AppDesign.visualStyle(): BookWaveDesign = when (this) {
    AppDesign.Classic -> BookWaveDesign.Classic
    AppDesign.Minimal -> BookWaveDesign.Minimal
    AppDesign.Editorial -> BookWaveDesign.Editorial
}
