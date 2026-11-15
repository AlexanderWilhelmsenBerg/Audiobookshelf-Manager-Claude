package com.example.shelfplayer.feature.settings

import androidx.lifecycle.ViewModel
import com.example.shelfplayer.playback.SystemRouteExperiment
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SystemRouteExperimentViewModel @Inject constructor(val experiment: SystemRouteExperiment) : ViewModel() {
    override fun onCleared() = experiment.close()
}
