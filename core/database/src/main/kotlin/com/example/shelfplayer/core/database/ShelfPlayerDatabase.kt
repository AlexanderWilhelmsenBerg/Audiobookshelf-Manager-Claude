package com.example.shelfplayer.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.shelfplayer.core.database.converter.StringListConverters
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
import com.example.shelfplayer.core.database.entity.AudioTrackEntity
import com.example.shelfplayer.core.database.entity.AuthorEntity
import com.example.shelfplayer.core.database.entity.BookAuthorCrossRef
import com.example.shelfplayer.core.database.entity.BookEntity
import com.example.shelfplayer.core.database.entity.BookPlaybackSettingsEntity
import com.example.shelfplayer.core.database.entity.BookSeriesCrossRef
import com.example.shelfplayer.core.database.entity.BookmarkEntity
import com.example.shelfplayer.core.database.entity.ChapterEntity
import com.example.shelfplayer.core.database.entity.DownloadRequestEntity
import com.example.shelfplayer.core.database.entity.DownloadedBookEntity
import com.example.shelfplayer.core.database.entity.DownloadedFileEntity
import com.example.shelfplayer.core.database.entity.LibraryEntity
import com.example.shelfplayer.core.database.entity.MediaProgressEntity
import com.example.shelfplayer.core.database.entity.PlaybackHistoryEntity
import com.example.shelfplayer.core.database.entity.PlaybackSessionEntity
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.database.entity.ProfileVisibleBookEntity
import com.example.shelfplayer.core.database.entity.SeriesEntity
import com.example.shelfplayer.core.database.entity.ServerEntity
import com.example.shelfplayer.core.database.entity.SleepTimerSessionEntity
import com.example.shelfplayer.core.database.entity.SyncStateEntity

/**
 * PRODUCT_SPEC 9.1 — Room is the source of truth for everything the UI displays.
 *
 * PRODUCT_SPEC 13.1 / 22.11: destructive migration is prohibited. `fallbackToDestructiveMigration`
 * appears nowhere in this repository, and `Migrations.ALL` is what every new version must extend.
 * The schema for each version is exported to `core/database/schemas` and committed, which is what
 * makes a migration reviewable and testable.
 *
 * When bumping [DATABASE_VERSION], also update `databaseVersion` in `core/database/build.gradle.kts`
 * so `verifyRoomSchemas` checks the new file.
 *
 * ### Never edit a committed schema. Bump instead.
 *
 * A `schemas/…/N.json` that has been built into an APK is **a fact about somebody's phone**, not a working
 * file. Room stores each version's identity hash in `room_master_table` and compares it on open; change a
 * version's schema in place and the app crashes at startup on exactly the devices that ran the previous
 * build, with a hash it has no migration to reach. It is invisible on a fresh install and on every test that
 * starts from an older version, which is what makes it easy to ship.
 *
 * This happened on 2026-08-14: version 14 shipped in build 0.9.2, the next commit removed a column and left
 * the version at 14 on the reasoning that 14 "had not shipped", and the owner's phone would not start.
 * `MigrationTest` now opens a database shaped as that build left it, so the mistake fails a test rather than
 * a device. "Has it been built into an APK?" is the only question that matters, and if the answer is unclear
 * the answer is yes.
 */
@Database(
    entities = [
        ServerEntity::class,
        ProfileEntity::class,
        LibraryEntity::class,
        AuthorEntity::class,
        SeriesEntity::class,
        BookEntity::class,
        BookAuthorCrossRef::class,
        BookSeriesCrossRef::class,
        AudioTrackEntity::class,
        ChapterEntity::class,
        MediaProgressEntity::class,
        PlaybackSessionEntity::class,
        ProfileVisibleBookEntity::class,
        SyncStateEntity::class,
        SleepTimerSessionEntity::class,
        PlaybackHistoryEntity::class,
        BookmarkEntity::class,
        BookPlaybackSettingsEntity::class,
        DownloadedBookEntity::class,
        DownloadedFileEntity::class,
        DownloadRequestEntity::class,
    ],
    version = DATABASE_VERSION,
    exportSchema = true,
)
@TypeConverters(StringListConverters::class)
abstract class ShelfPlayerDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    abstract fun libraryWriteDao(): LibraryWriteDao

    abstract fun profileDao(): ProfileDao

    abstract fun progressDao(): ProgressDao

    abstract fun sessionOutboxDao(): SessionOutboxDao

    abstract fun sleepTimerDao(): SleepTimerDao

    abstract fun playbackHistoryDao(): PlaybackHistoryDao

    abstract fun bookmarkDao(): BookmarkDao

    abstract fun bookPlaybackSettingsDao(): BookPlaybackSettingsDao

    abstract fun downloadDao(): DownloadDao

    abstract fun syncStateDao(): SyncStateDao

    companion object {
        const val NAME: String = "shelfplayer.db"
    }
}

internal const val DATABASE_VERSION = 17
