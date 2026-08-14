package com.example.shelfplayer.data.library.di

import com.example.shelfplayer.data.library.DefaultBookmarkRepository
import com.example.shelfplayer.data.library.DefaultLibraryRepository
import com.example.shelfplayer.data.library.DefaultPlaybackHistoryRepository
import com.example.shelfplayer.data.library.DefaultPlaybackRepository
import com.example.shelfplayer.data.library.DefaultProfileRepository
import com.example.shelfplayer.data.library.DefaultRealtimeUpdates
import com.example.shelfplayer.data.library.DefaultSessionSyncRepository
import com.example.shelfplayer.domain.realtime.RealtimeUpdates
import com.example.shelfplayer.domain.repository.BookmarkRepository
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.repository.SessionSyncRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** PRODUCT_SPEC 9.3 — data modules implement the domain's repository interfaces. */
@Module
@InstallIn(SingletonComponent::class)
interface LibraryDataModule {
    @Binds
    @Singleton
    fun bindsRealtimeUpdates(impl: DefaultRealtimeUpdates): RealtimeUpdates

    @Binds
    @Singleton
    fun bindsLibraryRepository(impl: DefaultLibraryRepository): LibraryRepository

    @Binds
    @Singleton
    fun bindsProfileRepository(impl: DefaultProfileRepository): ProfileRepository

    @Binds
    @Singleton
    fun bindsPlaybackRepository(impl: DefaultPlaybackRepository): PlaybackRepository

    /** PRODUCT_SPEC PLAY-003 — the jumps a listener has made, so a seek has an undo. */
    @Binds
    @Singleton
    fun bindsPlaybackHistoryRepository(impl: DefaultPlaybackHistoryRepository): PlaybackHistoryRepository

    /** PRODUCT_SPEC 11.1 — the positions a listener wanted to keep, and their own words about them. */
    @Binds
    @Singleton
    fun bindsBookmarkRepository(impl: DefaultBookmarkRepository): BookmarkRepository

    @Binds
    @Singleton
    fun bindsSessionSyncRepository(impl: DefaultSessionSyncRepository): SessionSyncRepository
}
