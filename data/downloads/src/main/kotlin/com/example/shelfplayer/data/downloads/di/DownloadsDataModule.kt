package com.example.shelfplayer.data.downloads.di

import com.example.shelfplayer.data.downloads.AndroidMediaContainerVerifier
import com.example.shelfplayer.data.downloads.BookDownloader
import com.example.shelfplayer.data.downloads.DefaultDownloadRepository
import com.example.shelfplayer.data.downloads.MediaContainerVerifier
import com.example.shelfplayer.domain.download.OfflineFiles
import com.example.shelfplayer.domain.repository.DownloadRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** PRODUCT_SPEC 9.3 — data modules implement the domain's repository interfaces. */
@Module
@InstallIn(SingletonComponent::class)
interface DownloadsDataModule {
    @Binds
    @Singleton
    fun bindsDownloadRepository(impl: DefaultDownloadRepository): DownloadRepository

    /**
     * PRODUCT_SPEC DL-002 — the "readable media container" check.
     *
     * Bound rather than injected directly so a test can substitute the decision. Robolectric has no media
     * stack, so the real implementation answers `false` for everything there and every downloader test
     * would assert a refusal.
     */
    @Binds
    @Singleton
    fun bindsMediaContainerVerifier(impl: AndroidMediaContainerVerifier): MediaContainerVerifier

    /** PRODUCT_SPEC DL-003 — the filesystem half, kept apart from the manifest half. */
    @Binds
    @Singleton
    fun bindsOfflineFiles(impl: BookDownloader): OfflineFiles
}
