package com.example.shelfplayer.data.settings.transfer

import android.content.Context
import androidx.datastore.core.DataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
import com.example.shelfplayer.core.database.ShelfPlayerDatabase
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.database.entity.ServerEntity
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.datastore.AppSettingsSerializer
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.playback.PlaybackSpeed
import com.example.shelfplayer.core.model.settings.AppLanguage
import com.example.shelfplayer.core.testing.RecordingLogSink
import com.example.shelfplayer.core.testing.TestAppClock
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC SET-001 — the settings export and import, against a real store and a real database.
 *
 * Real storage for the reason `DefaultSleepTimerRepositoryTest` gives: the properties worth testing are
 * what the *store* does — that a merge is one transaction, that an unset value reads back as a default —
 * and a fake would only reproduce whatever this file already believed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultSettingsTransferRepositoryTest {

    private lateinit var database: ShelfPlayerDatabase
    private lateinit var settings: AppSettingsDataSource
    private lateinit var repository: DefaultSettingsTransferRepository
    private lateinit var storeFile: File
    private lateinit var storeScope: CoroutineScope
    private val sink = RecordingLogSink()

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ShelfPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storeFile = File(context.cacheDir, "settings-transfer-test.pb").also(File::delete)
        storeScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        settings = AppSettingsDataSource(
            dataStore = DataStoreFactory.create(
                serializer = AppSettingsSerializer(),
                scope = storeScope,
                produceFile = { storeFile },
            ),
            logger = RedactingLogger(sink, DefaultRedactor(RedactionPolicy.Default)),
        )
        repository = DefaultSettingsTransferRepository(
            settings = settings,
            profiles = database.profileDao(),
            clock = TestAppClock(),
            logger = RedactingLogger(sink, DefaultRedactor(RedactionPolicy.Default)),
        )
        seedAccount()
    }

    @After
    fun tearDown() {
        database.close()
        storeScope.cancel()
        storeFile.delete()
    }

    /**
     * The whole point, end to end: settings out, settings back, on an install that shares nothing with the
     * one that wrote them.
     *
     * The second store is a genuinely different file with its own device id, because the property that
     * matters most is the one a same-store round trip could not show — that the *importing* install keeps
     * its own identity.
     */
    @Test
    fun `an export restores the settings without adopting the exporting device's identity`() = runTest {
        settings.setDefaultSpeed(PlaybackSpeed.of(1.6f))
        settings.setAppLanguage(AppLanguage.ofTag("nb"))
        val exportingDeviceId = settings.playbackDeviceId { "device-that-exported" }

        val exported = assertIs<AppResult.Success<*>>(repository.export())
        val document = (exported.value as com.example.shelfplayer.core.model.settings.SettingsExport).document

        val fresh = freshInstall()
        val importingDeviceId = fresh.store.playbackDeviceId { "device-that-imported" }
        assertIs<AppResult.Success<*>>(fresh.repository.import(document))

        val after = fresh.store.current()
        assertEquals(160, after.defaultSpeedHundredths, "the speed came across")
        assertEquals("nb", after.appLanguageTag, "the language came across")
        assertEquals(importingDeviceId, after.playbackDeviceId, "the importing install kept its own device id")
        assertFalse(after.playbackDeviceId == exportingDeviceId, "and did not adopt the exporting one")
        fresh.close()
    }

    /**
     * PRODUCT_SPEC AUTH-003 / `docs/risks.md` R-20 — the file names servers and carries no way into them.
     *
     * Asserted on the text rather than on a type, because the promise is about the file: anybody who opens
     * it should find an address and no credential. The device id is checked here too — it is not a secret,
     * but it is an identifier the server groups listening sessions by, and it must not travel.
     */
    @Test
    fun `the exported file carries the server address and nothing that could sign in`() = runTest {
        settings.playbackDeviceId { "device-abc123" }

        val exported = assertIs<AppResult.Success<*>>(repository.export())
        val document = (exported.value as com.example.shelfplayer.core.model.settings.SettingsExport).document

        assertTrue(document.contains("https://books.example"), "the address is the point of the file")
        assertFalse(document.contains("device-abc123"), "the playback device id must not travel")
        // The `about` line is dropped before the scan, and only that line: it is a constant whose whole
        // job is to *say* that no password or access token is in the file, so it names the words the scan
        // looks for. Everything else stays in, including every value the store contributed.
        val data = document.lines().filterNot { it.trimStart().startsWith(""""about"""") }.joinToString("\n")
        listOf("token", "password", "passcode", "secret", "Bearer").forEach { word ->
            assertFalse(data.contains(word, ignoreCase = true), "the file must not mention $word")
        }
    }

    /**
     * A per-account preference restores for an account that exists here, and is *counted* rather than
     * silently dropped for one that does not.
     *
     * The second half is the one worth a test: on a fresh install every block is skipped, and a UI that
     * reported a blanket success would be claiming something that did not happen.
     */
    @Test
    fun `per-account preferences apply where the account exists and are counted where it does not`() = runTest {
        settings.setDefaultLibrary(ProfileId(PROFILE), LibraryId("library-1"))
        val exported = assertIs<AppResult.Success<*>>(repository.export())
        val document = (exported.value as com.example.shelfplayer.core.model.settings.SettingsExport).document

        val applied = assertIs<AppResult.Success<*>>(repository.import(document))
        val appliedSummary = applied.value as com.example.shelfplayer.core.model.settings.SettingsImport
        assertEquals(1, appliedSummary.profilePreferencesApplied)
        assertEquals(0, appliedSummary.profilePreferencesSkipped)
        assertEquals("library-1", settings.profilePreferences(ProfileId(PROFILE)).first().defaultLibraryId?.value)

        val fresh = freshInstall()
        val onFresh = assertIs<AppResult.Success<*>>(fresh.repository.import(document))
        val freshSummary = onFresh.value as com.example.shelfplayer.core.model.settings.SettingsImport
        assertEquals(0, freshSummary.profilePreferencesApplied, "no account here to restore it onto")
        assertEquals(1, freshSummary.profilePreferencesSkipped, "and the UI is told so")
        assertEquals(listOf("https://books.example"), freshSummary.serverUrls)
        fresh.close()
    }

    /** A file that is not one of ours fails as something the user can act on, not as an unknown error. */
    @Test
    fun `a file that is not a settings export is a validation failure`() = runTest {
        val failure = assertIs<AppResult.Failure>(repository.import("this is not json"))
        assertIs<AppError.Validation>(failure.error)
    }

    /** A file from a later build applies what this one understands rather than refusing the whole import. */
    @Test
    fun `an unknown field does not stop the rest of the file importing`() = runTest {
        val document = """
            {
              "format_version": 1,
              "settings": { "default_speed_hundredths": 175, "a_setting_from_a_later_build": true }
            }
        """.trimIndent()

        assertIs<AppResult.Success<*>>(repository.import(document))

        assertEquals(175, settings.current().defaultSpeedHundredths)
    }

    /** A second install: its own store file, its own device id, the same database of accounts or not. */
    private fun freshInstall(): Install {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "settings-transfer-fresh.pb").also(File::delete)
        val scope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        val logger = RedactingLogger(sink, DefaultRedactor(RedactionPolicy.Default))
        val store = AppSettingsDataSource(
            dataStore = DataStoreFactory.create(
                serializer = AppSettingsSerializer(),
                scope = scope,
                produceFile = { file },
            ),
            logger = logger,
        )
        val empty = Room.inMemoryDatabaseBuilder(context, ShelfPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        return Install(
            store = store,
            repository = DefaultSettingsTransferRepository(
                settings = store,
                profiles = empty.profileDao(),
                clock = TestAppClock(),
                logger = logger,
            ),
            close = {
                empty.close()
                scope.cancel()
                file.delete()
            },
        )
    }

    private class Install(
        val store: AppSettingsDataSource,
        val repository: DefaultSettingsTransferRepository,
        private val close: () -> Unit,
    ) {
        fun close() = close.invoke()
    }

    private suspend fun seedAccount() {
        database.profileDao().upsertServer(
            ServerEntity(
                serverId = SERVER,
                displayName = "Demo",
                baseUrl = "https://books.example",
                detectedVersion = "2.36.0",
                isFixture = false,
                lastFetchedAt = 0,
                authMethodsJson = "[]",
                capabilitiesJson = "[]",
                capabilitiesDetectedAt = null,
            ),
        )
        database.profileDao().upsertProfile(
            ProfileEntity(
                profileId = PROFILE,
                serverId = SERVER,
                remoteUserId = null,
                username = "kari",
                displayName = "Kari",
                role = "Listener",
                requiresReauthentication = false,
                lastUsedAt = null,
                isFixture = false,
                accessibleLibrariesJson = "[]",
                hasAllLibraryAccess = true,
                hasAllTagAccess = true,
                canDownload = false,
            ),
        )
    }

    private companion object {
        const val SERVER = "server-1"
        const val PROFILE = "profile-1"
    }
}
