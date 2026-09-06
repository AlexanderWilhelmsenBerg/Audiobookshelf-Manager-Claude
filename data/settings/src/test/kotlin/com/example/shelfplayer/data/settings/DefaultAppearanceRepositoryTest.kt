package com.example.shelfplayer.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
import com.example.shelfplayer.core.datastore.AppSettings
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.datastore.AppSettingsSerializer
import com.example.shelfplayer.core.datastore.ThemeMode
import com.example.shelfplayer.core.model.settings.AppTheme
import com.example.shelfplayer.core.model.settings.BackgroundTheme
import com.example.shelfplayer.core.testing.RecordingLogSink
import com.example.shelfplayer.domain.settings.BackgroundThemeCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals

/**
 * SET-002 migration compatibility at the boundary that now owns it.
 *
 * These use a real DataStore rather than an appearance fake because every assertion is about how values
 * written by an older/newer build are interpreted after an upgrade or downgrade.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultAppearanceRepositoryTest {

    private lateinit var dataStore: DataStore<AppSettings>
    private lateinit var settings: AppSettingsDataSource
    private lateinit var repository: DefaultAppearanceRepository
    private lateinit var storeScope: CoroutineScope
    private lateinit var storeFile: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        storeFile = File(context.cacheDir, "appearance-repository-test.pb").also(File::delete)
        storeScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        dataStore = DataStoreFactory.create(
            serializer = AppSettingsSerializer(),
            scope = storeScope,
            produceFile = { storeFile },
        )
        val logger = RedactingLogger(
            RecordingLogSink(),
            DefaultRedactor(RedactionPolicy.Default),
        )
        settings = AppSettingsDataSource(dataStore = dataStore, logger = logger)
        repository = DefaultAppearanceRepository(
            settings = settings,
            backgroundThemes = EmptyCatalog,
            logger = logger,
        )
    }

    @After
    fun tearDown() {
        storeScope.cancel()
        storeFile.delete()
    }

    @Test
    fun `a stored app theme key wins over legacy brightness`() = runTest {
        settings.setThemeMode(ThemeMode.THEME_MODE_LIGHT)
        dataStore.updateData { stored ->
            stored.toBuilder().setAppThemeKey(AppTheme.Amoled.key).build()
        }

        assertEquals(AppTheme.Amoled, repository.observeAppearance().first().theme)
    }

    @Test
    fun `an install that predates the key keeps the brightness it chose`() = runTest {
        settings.setThemeMode(ThemeMode.THEME_MODE_DARK)
        assertEquals(AppTheme.Dark, repository.observeAppearance().first().theme)

        settings.setThemeMode(ThemeMode.THEME_MODE_LIGHT)
        assertEquals(AppTheme.Light, repository.observeAppearance().first().theme)
    }

    @Test
    fun `an unwritten or unknown legacy setting follows the system`() = runTest {
        assertEquals(AppTheme.System, repository.observeAppearance().first().theme)

        settings.setThemeMode(ThemeMode.THEME_MODE_SYSTEM)
        assertEquals(AppTheme.System, repository.observeAppearance().first().theme)

        // A numeric value from a newer proto is exposed by protobuf as UNRECOGNIZED on this build.
        dataStore.updateData { stored -> stored.toBuilder().setThemeModeValue(999).build() }
        assertEquals(AppTheme.System, repository.observeAppearance().first().theme)
    }

    @Test
    fun `an unrecognised app theme key falls back rather than throwing`() = runTest {
        dataStore.updateData { stored -> stored.toBuilder().setAppThemeKey("solarized").build() }

        assertEquals(AppTheme.System, repository.observeAppearance().first().theme)
    }

    private object EmptyCatalog : BackgroundThemeCatalog {
        override suspend fun themes(): List<BackgroundTheme> = emptyList()
        override suspend fun theme(id: String): BackgroundTheme? = null
    }
}
