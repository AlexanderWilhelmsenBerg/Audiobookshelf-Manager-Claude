package com.example.shelfplayer.data.settings

import android.content.Context
import androidx.datastore.core.DataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.datastore.AppSettingsSerializer
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.testing.RecordingLogSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultRememberedBookRepositoryTest {

    private lateinit var settings: AppSettingsDataSource
    private lateinit var repository: DefaultRememberedBookRepository
    private lateinit var storeScope: CoroutineScope
    private lateinit var storeFile: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        storeFile = File(context.cacheDir, "remembered-book-test.pb").also(File::delete)
        storeScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        val logger = RedactingLogger(RecordingLogSink(), DefaultRedactor(RedactionPolicy.Default))
        settings = AppSettingsDataSource(
            dataStore = DataStoreFactory.create(
                serializer = AppSettingsSerializer(),
                scope = storeScope,
                produceFile = { storeFile },
            ),
            logger = logger,
        )
        repository = DefaultRememberedBookRepository(settings, logger)
    }

    @After
    fun tearDown() {
        storeScope.cancel()
        storeFile.delete()
    }

    @Test
    fun `an upgraded profile has no invented remembered book`() = runTest {
        assertNull(repository.rememberedBook(PROFILE_A))
    }

    @Test
    fun `profiles keep independent remembered identities`() = runTest {
        assertIs<AppResult.Success<Unit>>(repository.remember(PROFILE_A, BOOK_A))
        assertIs<AppResult.Success<Unit>>(repository.remember(PROFILE_B, BOOK_B))

        assertEquals(BOOK_A, repository.rememberedBook(PROFILE_A))
        assertEquals(BOOK_B, repository.rememberedBook(PROFILE_B))
    }

    @Test
    fun `the remembered identity is written to durable proto storage`() = runTest {
        repository.remember(PROFILE_A, BOOK_A)

        val stored = storeFile.inputStream().use { AppSettingsSerializer().readFrom(it) }

        assertEquals(BOOK_A.value, stored.profileSettingsMap[PROFILE_A.value]?.rememberedBookId)
    }

    @Test
    fun `clearing a removed profile also clears its remembered identity`() = runTest {
        repository.remember(PROFILE_A, BOOK_A)

        repository.forget(PROFILE_A)

        assertNull(repository.rememberedBook(PROFILE_A))
    }

    private companion object {
        val PROFILE_A = ProfileId("profile-a")
        val PROFILE_B = ProfileId("profile-b")
        val BOOK_A = LibraryItemId("book-a")
        val BOOK_B = LibraryItemId("book-b")
    }
}
