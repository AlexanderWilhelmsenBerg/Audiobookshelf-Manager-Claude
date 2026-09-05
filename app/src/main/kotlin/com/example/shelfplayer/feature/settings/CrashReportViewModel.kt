package com.example.shelfplayer.feature.settings

import androidx.lifecycle.ViewModel
import com.example.shelfplayer.diagnostics.CrashReportStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** The last fatal-process report, if one exists, and the one explicit way to remove it. */
@HiltViewModel
class CrashReportViewModel @Inject constructor(private val reports: CrashReportStore) : ViewModel() {
    val report: StateFlow<String?> = reports.report

    fun onClear() = reports.clear()
}
