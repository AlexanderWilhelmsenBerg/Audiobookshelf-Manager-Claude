package com.example.shelfplayer.core.datastore

import androidx.datastore.core.DataStore
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.SeriesId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeriesOrderSettingsTest {
    @Test
    fun `missing numbers survive upgrade bytes and stay scoped to the profile and series`() = runTest {
        val primary = ProfileId("primary")
        val second = ProfileId("secondary")
        val series = SeriesId("series")
        val another = SeriesId("another")
        val old = AppSettings.newBuilder().setActiveProfileId(primary.value).setDynamicColor(true).build()
        val first = source(AppSettings.parseFrom(old.toByteArray()))
        assertEquals(emptySet(), first.observeMissingSeriesBooks(primary, series).first())
        assertTrue(first.setMissingSeriesBooks(primary, series, setOf(4, 7)))
        assertTrue(first.setMissingSeriesBooks(primary, another, setOf(2)))
        assertFalse(first.setMissingSeriesBooks(second, series, setOf(8)))

        val restarted = source(AppSettings.parseFrom(first.current().toByteArray()))
        assertEquals(setOf(4, 7), restarted.observeMissingSeriesBooks(primary, series).first())
        assertEquals(setOf(2), restarted.observeMissingSeriesBooks(primary, another).first())
        assertEquals(emptySet(), restarted.observeMissingSeriesBooks(second, series).first())
        assertTrue(restarted.current().dynamicColor)

        assertTrue(restarted.setMissingSeriesBooks(primary, series, emptySet()))
        assertEquals(emptySet(), restarted.observeMissingSeriesBooks(primary, series).first())
        restarted.clearProfilePreferences(primary)
        assertEquals(emptySet(), restarted.observeMissingSeriesBooks(primary, another).first())
    }

    private fun source(initial: AppSettings): AppSettingsDataSource = AppSettingsDataSource(
        dataStore = object : DataStore<AppSettings> {
            override val data = MutableStateFlow(initial)

            override suspend fun updateData(transform: suspend (t: AppSettings) -> AppSettings): AppSettings =
                transform(data.value).also { data.value = it }
        },
        logger = object : Logger {
            override fun log(event: LogEvent) = Unit
        },
    )
}
