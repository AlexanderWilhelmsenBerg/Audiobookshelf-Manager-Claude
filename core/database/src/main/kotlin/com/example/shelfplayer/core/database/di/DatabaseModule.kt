package com.example.shelfplayer.core.database.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.shelfplayer.core.database.DatabaseTransactionRunner
import com.example.shelfplayer.core.database.RoomDatabaseTransactionRunner
import com.example.shelfplayer.core.database.ShelfPlayerDatabase
import com.example.shelfplayer.core.database.dao.LibraryDao
import com.example.shelfplayer.core.database.dao.LibraryWriteDao
import com.example.shelfplayer.core.database.dao.ProfileDao
import com.example.shelfplayer.core.database.dao.ProgressDao
import com.example.shelfplayer.core.database.dao.SleepTimerDao
import com.example.shelfplayer.core.database.dao.SyncStateDao
import com.example.shelfplayer.core.database.migration.Migrations
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface DatabaseModule {
    /**
     * PRODUCT_SPEC 9.3 — data modules get transactions without Room on their compile classpath.
     */
    @Binds
    @Singleton
    fun bindsTransactionRunner(impl: RoomDatabaseTransactionRunner): DatabaseTransactionRunner

    companion object {
        /**
         * PRODUCT_SPEC 13.1 / 22.11 — no destructive migration, in any build type.
         *
         * There is intentionally no `fallbackToDestructiveMigration()` call here. If a future schema
         * change ships without a migration the app fails loudly on open, rather than quietly deleting
         * the user's progress and download manifests.
         *
         * Room enables SQLite foreign-key enforcement for us, which is what makes the `CASCADE` rules on
         * the entities real: PRODUCT_SPEC AUTH-002 ("removing one profile does not remove another
         * profile's data") depends on the database enforcing them, not on callers remembering to.
         */
        @Provides
        @Singleton
        fun providesDatabase(@ApplicationContext context: Context): ShelfPlayerDatabase = Room
            .databaseBuilder(context, ShelfPlayerDatabase::class.java, ShelfPlayerDatabase.NAME)
            .apply { Migrations.ALL.forEach(::addMigrations) }
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

        @Provides
        fun providesLibraryDao(database: ShelfPlayerDatabase): LibraryDao = database.libraryDao()

        @Provides
        fun providesLibraryWriteDao(database: ShelfPlayerDatabase): LibraryWriteDao = database.libraryWriteDao()

        @Provides
        fun providesProfileDao(database: ShelfPlayerDatabase): ProfileDao = database.profileDao()

        @Provides
        fun providesProgressDao(database: ShelfPlayerDatabase): ProgressDao = database.progressDao()

        @Provides
        fun providesSleepTimerDao(database: ShelfPlayerDatabase): SleepTimerDao = database.sleepTimerDao()

        @Provides
        fun providesSyncStateDao(database: ShelfPlayerDatabase): SyncStateDao = database.syncStateDao()
    }
}
