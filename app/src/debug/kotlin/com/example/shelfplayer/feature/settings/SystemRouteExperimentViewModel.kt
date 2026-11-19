package com.example.shelfplayer.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import com.example.shelfplayer.playback.AutomaticHeadsetTakeBackSetting
import com.example.shelfplayer.playback.SystemRouteExperiment
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SystemRouteExperimentViewModel @Inject constructor(
    val experiment: SystemRouteExperiment,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    private val automatic = MutableStateFlow(AutomaticHeadsetTakeBackSetting.enabled(context))
    val automaticTakeBack = automatic.asStateFlow()

    fun setAutomaticTakeBack(enabled: Boolean) {
        AutomaticHeadsetTakeBackSetting.setEnabled(context, enabled)
        automatic.value = enabled
    }

    override fun onCleared() = experiment.close()
}
