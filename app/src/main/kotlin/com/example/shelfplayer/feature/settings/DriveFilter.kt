package com.example.shelfplayer.feature.settings

import androidx.annotation.StringRes
import com.example.shelfplayer.R
import com.example.shelfplayer.playback.AndroidAutoDrive

internal enum class DriveFilter(@get:StringRes val label: Int) {
    All(R.string.auto_filter_all),
    With(R.string.auto_filter_with),
    Without(R.string.auto_filter_without),
    Unknown(R.string.auto_filter_unknown),
    ;

    fun includes(drive: AndroidAutoDrive): Boolean = when (this) {
        All -> true
        With -> drive.headsetPresent == true
        Without -> drive.headsetPresent == false
        Unknown -> drive.headsetPresent == null
    }
}
