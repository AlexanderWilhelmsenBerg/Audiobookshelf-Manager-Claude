package com.example.shelfplayer.playback

import android.annotation.SuppressLint
import android.content.Context
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.log.warn
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debug-only persistent one-shot used to reproduce issue #138 on a real MediaSession path.
 *
 * SharedPreferences is intentional here: `adb install -r` and process death preserve app data, while an
 * uninstall or clear-data removes the diagnostic. The consumption write uses `commit()` before returning
 * true so a crash immediately after fault injection cannot accidentally leave the next cold resume armed.
 */
@Singleton
class DebugColdResumeDiagnostic @Inject constructor(@ApplicationContext context: Context, private val logger: Logger) :
    ColdResumeDiagnostic {
    private val preferences = context.getSharedPreferences(COLD_RESUME_DIAGNOSTIC_PREFERENCES, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(readState())

    override val state: StateFlow<ColdResumeDiagnosticState> = mutableState

    @Synchronized
    override fun arm() {
        if (!writeState(ColdResumeDiagnosticState.Armed)) {
            logger.warn(LogCategory.Playback, "Cold-resume diagnostic could not be armed")
            return
        }
        mutableState.value = ColdResumeDiagnosticState.Armed
        logger.info(LogCategory.Playback, "Cold-resume diagnostic armed")
    }

    @Synchronized
    override fun consumeForColdPlaybackResumption(): Boolean {
        if (mutableState.value != ColdResumeDiagnosticState.Armed) return false
        if (!writeState(ColdResumeDiagnosticState.Consumed)) {
            logger.warn(LogCategory.Playback, "Cold-resume diagnostic consumption could not be persisted")
            return false
        }
        mutableState.value = ColdResumeDiagnosticState.Consumed
        logger.info(LogCategory.Playback, "Cold-resume diagnostic consumed by cold playback resumption")
        return true
    }

    private fun readState(): ColdResumeDiagnosticState {
        val stored = preferences.getString(COLD_RESUME_DIAGNOSTIC_STATE, null)
        return ColdResumeDiagnosticState.entries.firstOrNull { it.name == stored }
            ?: ColdResumeDiagnosticState.NotArmed
    }

    // Synchronous persistence is deliberate for this one-shot fault injector: consumption must be durable
    // before the injected zero is returned, otherwise an immediate process death could re-arm it.
    @SuppressLint("UseKtx", "ApplySharedPref")
    private fun writeState(state: ColdResumeDiagnosticState): Boolean =
        preferences.edit().putString(COLD_RESUME_DIAGNOSTIC_STATE, state.name).commit()
}

@Module
@InstallIn(SingletonComponent::class)
interface ColdResumeDiagnosticModule {
    @Binds
    @Singleton
    fun bindColdResumeDiagnostic(implementation: DebugColdResumeDiagnostic): ColdResumeDiagnostic
}

internal const val COLD_RESUME_DIAGNOSTIC_PREFERENCES = "playback_cold_resume_diagnostic"
private const val COLD_RESUME_DIAGNOSTIC_STATE = "state"
