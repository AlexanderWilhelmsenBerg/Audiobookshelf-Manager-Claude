package com.example.shelfplayer.core.datastore

import androidx.datastore.core.DataStoreFactory
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** BW-PLAY-01 — durable remembered identity survives a store reopen and stays profile-scoped. */
class RememberedBookStorageTest {

    @Test
    fun `remembered identities survive reopen and deleting one profile cannot affect another`() = runTest {
        val directory = createTempDir(prefix = "remembered-book-")
        val file = File(directory, AppSettingsSerializer.FILE_NAME)
        var scope: CoroutineScope? = null

        try {
            scope = storeScope()
            val first = dataSource(file, scope)

            assertNull(first.rememberedBookId(PROFILE_A).first())
            assertNull(first.rememberedBookId(PROFILE_B).first())

            first.setRememberedBookId(PROFILE_A, BOOK_A)
            first.setRememberedBookId(PROFILE_B, BOOK_B)

            assertEquals(BOOK_A, first.rememberedBookId(PROFILE_A).first())
            assertEquals(BOOK_B, first.rememberedBookId(PROFILE_B).first())

            scope.coroutineContext[Job]?.cancelAndJoin()
            scope = storeScope()
            val reopened = dataSource(file, scope)

            assertEquals(BOOK_A, reopened.rememberedBookId(PROFILE_A).first(), "profile A survives process/store reopen")
            assertEquals(BOOK_B, reopened.rememberedBookId(PROFILE_B).first(), "profile B survives process/store reopen")

            // RemoveProfileUseCase already clears this whole ProfileSettings entry through
            // PreferencesRepository.forget(profileId). Pin the storage half here: the remembered id leaves
            // with A while B's independently keyed value remains intact.
            reopened.clearProfilePreferences(PROFILE_A)

            assertNull(reopened.rememberedBookId(PROFILE_A).first())
            assertEquals(BOOK_B, reopened.rememberedBookId(PROFILE_B).first())
        } finally {
            scope?.coroutineContext?.get(Job)?.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    private fun dataSource(file: File, scope: CoroutineScope) = AppSettingsDataSource(
        dataStore = DataStoreFactory.create(
            serializer = AppSettingsSerializer(),
            scope = scope,
            produceFile = { file },
        ),
        logger = NO_OP_LOGGER,
    )

    private fun storeScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        val PROFILE_A = ProfileId("profile-a")
        val PROFILE_B = ProfileId("profile-b")
        val BOOK_A = LibraryItemId("book-a")
        val BOOK_B = LibraryItemId("book-b")
        val NO_OP_LOGGER = object : Logger {
            override fun log(event: LogEvent) = Unit
        }
    }
}
