package com.example.shelfplayer.core.database.di

import com.example.shelfplayer.core.database.ShelfPlayerDatabase
import com.example.shelfplayer.core.database.dao.BookPlaybackSettingsDao
import com.example.shelfplayer.core.database.dao.BookmarkDao
import com.example.shelfplayer.core.database.dao.DownloadDao
import com.example.shelfplayer.core.database.dao.LibraryDao
import com.example.shelfplayer.core.database.dao.LibraryWriteDao
import com.example.shelfplayer.core.database.dao.PlaybackHistoryDao
import com.example.shelfplayer.core.database.dao.ProfileDao
import com.example.shelfplayer.core.database.dao.ProgressDao
import com.example.shelfplayer.core.database.dao.SessionOutboxDao
import com.example.shelfplayer.core.database.dao.SleepTimerDao
import com.example.shelfplayer.core.database.dao.SyncStateDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The DAOs, one accessor each.
 *
 * Split from [DatabaseModule] when the eleventh one arrived. There is no design in the split beyond that —
 * every function here is `database.someDao()` — but a module that grows one mechanical line per table grows
 * without limit, and the useful thing in [DatabaseModule] is the *database* provider and the reasoning
 * attached to it. Keeping that file about one decision is worth a second file about none.
 *
 * Not `@Singleton`. A Room DAO is already a singleton inside the database, and annotating these would add a
 * second cache in front of a cache.
 */
@Module
@InstallIn(SingletonComponent::class)
object DaoModule {
    @Provides
    fun providesLibraryDao(database: ShelfPlayerDatabase): LibraryDao = database.libraryDao()

    @Provides
    fun providesLibraryWriteDao(database: ShelfPlayerDatabase): LibraryWriteDao = database.libraryWriteDao()

    @Provides
    fun providesBookPlaybackSettingsDao(database: ShelfPlayerDatabase): BookPlaybackSettingsDao =
        database.bookPlaybackSettingsDao()

    @Provides
    fun providesProfileDao(database: ShelfPlayerDatabase): ProfileDao = database.profileDao()

    @Provides
    fun providesProgressDao(database: ShelfPlayerDatabase): ProgressDao = database.progressDao()

    @Provides
    fun providesSessionOutboxDao(database: ShelfPlayerDatabase): SessionOutboxDao = database.sessionOutboxDao()

    @Provides
    fun providesSleepTimerDao(database: ShelfPlayerDatabase): SleepTimerDao = database.sleepTimerDao()

    @Provides
    fun providesPlaybackHistoryDao(database: ShelfPlayerDatabase): PlaybackHistoryDao = database.playbackHistoryDao()

    @Provides
    fun providesBookmarkDao(database: ShelfPlayerDatabase): BookmarkDao = database.bookmarkDao()

    @Provides
    fun providesDownloadDao(database: ShelfPlayerDatabase): DownloadDao = database.downloadDao()

    @Provides
    fun providesSyncStateDao(database: ShelfPlayerDatabase): SyncStateDao = database.syncStateDao()
}
