package com.example.shelfplayer.data.settings

import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC SET-001 — the settings store, behind the domain interface.
 *
 * A pass-through today. It earns its keep the moment PRODUCT_SPEC SET-001's precedence chain
 * (per-book, per-device, per-profile, global, product default) has more than one level: resolving that
 * is this class's job, not a ViewModel's.
 *
 * There is no `resultOf` here and no error branch. A DataStore read that fails already degrades to the
 * product defaults inside [AppSettingsDataSource] (PRODUCT_SPEC 2.1), and a write failure surfaces as
 * the exception it is rather than as a setting that silently did not take.
 */
@Singleton
class DefaultSettingsRepository @Inject constructor(private val settings: AppSettingsDataSource) : SettingsRepository {

    override val homeShowsLibraries: Flow<Boolean> = settings.homeShowsLibraries

    override suspend fun setHomeShowsLibraries(enabled: Boolean) {
        settings.setHomeShowsLibraries(enabled)
    }
}
