package com.example.shelfplayer.data.library.di

import com.example.shelfplayer.data.library.DefaultBookAssetSource
import com.example.shelfplayer.data.library.DefaultBookmarkRepository
import com.example.shelfplayer.data.library.DefaultLibraryRepository
import com.example.shelfplayer.data.library.DefaultMetadataRepository
import com.example.shelfplayer.data.library.DefaultPlaybackHistoryRepository
import com.example.shelfplayer.data.library.DefaultPlaybackRepository
import com.example.shelfplayer.data.library.DefaultProfileRepository
import com.example.shelfplayer.data.library.DefaultRealtimeUpdates
import com.example.shelfplayer.data.library.DefaultResumePolicyRepository
import com.example.shelfplayer.data.library.DefaultSessionSyncRepository
import com.example.shelfplayer.domain.download.BookAssetSource
import com.example.shelfplayer.domain.realtime.RealtimeUpdates
import com.example.shelfplayer.domain.repository.BookmarkRepository
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.MetadataRepository
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.repository.ResumePolicyRepository
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

    /**
     * PRODUCT_SPEC DL-001 — the catalogue's answer to "what is this book made of".
     *
     * Bound here rather than in `:data:downloads` because it reads the *library's* tracks, and the two
     * modules deliberately do not import each other.
     */
    @Binds
    @Singleton
    fun bindsBookAssetSource(impl: DefaultBookAssetSource): BookAssetSource

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

    /** Whether resume may follow newer Audiobookshelf listening activity from another client. */
    @Binds
    @Singleton
    fun bindsResumePolicyRepository(impl: DefaultResumePolicyRepository): ResumePolicyRepository

    /** PRODUCT_SPEC 11.1 — the positions a listener wanted to keep, and their own words about them. */
    @Binds
    @Singleton
    fun bindsBookmarkRepository(impl: DefaultBookmarkRepository): BookmarkRepository

    /** PRODUCT_SPEC MGR-001 — the metadata editor's repository. */
    @Binds
    @Singleton
    fun bindsMetadataRepository(impl: DefaultMetadataRepository): MetadataRepository

    @Binds
    @Singleton
    fun bindsSessionSyncRepository(impl: DefaultSessionSyncRepository): SessionSyncRepository
}
