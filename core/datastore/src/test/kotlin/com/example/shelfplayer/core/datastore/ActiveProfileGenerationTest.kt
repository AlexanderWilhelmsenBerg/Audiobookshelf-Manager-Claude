package com.example.shelfplayer.core.datastore

import androidx.datastore.core.DataStore
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.ProfileId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AUTH-002 / R-115 — mutation-time invalidation must survive conflated A → B → A observations. */
class ActiveProfileGenerationTest {
    @Test
    fun `switching away and back changes the token even without an active profile observer`() = runTest {
        val source = source()
        source.setActiveProfile(A)
        val before = source.activeProfileGeneration

        source.setActiveProfile(B)
        source.setActiveProfile(A)

        assertEquals(A.value, source.current().activeProfileId)
        assertTrue(source.activeProfileGeneration > before)
    }

    @Test
    fun `clearing an active profile invalidates the token but selecting it again is a no op`() = runTest {
        val source = source()
        source.setActiveProfile(A)
        val before = source.activeProfileGeneration
        source.setActiveProfile(A)
        assertEquals(before, source.activeProfileGeneration)

        source.clearActiveProfile()
        assertTrue(source.activeProfileGeneration > before)
        val cleared = source.activeProfileGeneration
        source.clearActiveProfile()
        assertEquals(cleared, source.activeProfileGeneration)
    }

    @Test
    fun `existing stored selection and preferences survive without a schema change`() = runTest {
        val existing = AppSettings.newBuilder().setActiveProfileId(A.value).setDynamicColor(true).build()
        val source = source(existing)
        source.setActiveProfile(B)

        assertEquals(B.value, source.current().activeProfileId)
        assertTrue(source.current().dynamicColor)
        assertTrue(source.activeProfileGeneration > 0)
    }

    private fun source(initial: AppSettings = AppSettings.getDefaultInstance()) = AppSettingsDataSource(
        dataStore = MemoryStore(initial),
        logger = object : Logger {
            override fun log(event: LogEvent) = Unit
        },
    )

    private class MemoryStore(initial: AppSettings) : DataStore<AppSettings> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<AppSettings> = state

        override suspend fun updateData(transform: suspend (t: AppSettings) -> AppSettings): AppSettings =
            transform(state.value).also { state.value = it }
    }

    private companion object {
        val A = ProfileId("profile-a")
        val B = ProfileId("profile-b")
    }
}
