package com.example.shelfplayer.feature.settings

import androidx.lifecycle.ViewModel
import com.example.shelfplayer.core.common.log.EventLog
import com.example.shelfplayer.core.common.log.LoggedEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * PRODUCT_SPEC 14.4 — what the app has been doing, readable on the device.
 *
 * The buffer is a process-lifetime ring held by [EventLog]; this is a window onto it. No state of its own,
 * because a log with a view model that filters or paginates is a log that can disagree with itself about
 * what happened.
 */
@HiltViewModel
class EventLogViewModel @Inject constructor(private val log: EventLog) : ViewModel() {
    val events: StateFlow<List<LoggedEvent>> = log.events

    fun onClear() = log.clear()
}
