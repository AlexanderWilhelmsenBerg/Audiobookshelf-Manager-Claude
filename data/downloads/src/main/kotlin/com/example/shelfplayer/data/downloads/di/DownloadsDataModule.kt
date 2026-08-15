package com.example.shelfplayer.data.downloads.di

import com.example.shelfplayer.data.downloads.AndroidMediaContainerVerifier
import com.example.shelfplayer.data.downloads.BookDownloader
import com.example.shelfplayer.data.downloads.DefaultDownloadRepository
import com.example.shelfplayer.data.downloads.DownloadRoots
import com.example.shelfplayer.data.downloads.DownloadVerifier
import com.example.shelfplayer.data.downloads.MediaContainerVerifier
import com.example.shelfplayer.data.downloads.StorageVolumes
import com.example.shelfplayer.domain.download.DownloadLocations
import com.example.shelfplayer.domain.download.OfflineFiles
import com.example.shelfplayer.domain.download.OfflineVerification
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

    /** PRODUCT_SPEC DL-002 — the start-up check and the diagnostics action. */
    @Binds
    @Singleton
    fun bindsOfflineVerification(impl: DownloadVerifier): OfflineVerification

    /**
     * PRODUCT_SPEC DL-003 / ADR-0020 — where downloads go, which is this module's decision to own.
     *
     * `StorageVolumes` is already the class that resolves a volume to a path for `DownloadStorage`; the
     * settings screen needs the same list and the same choice, so it reads the same object rather than a
     * parallel one that could disagree with it about which card is in the device.
     */
    @Binds
    @Singleton
    fun bindsDownloadLocations(impl: StorageVolumes): DownloadLocations

    /**
     * The same object again, behind the one-method seam `DownloadStorage` actually needs.
     *
     * Two bindings rather than injecting the class: `DownloadStorage` needs a list of directories and
     * nothing else, and a test that had to build a real `StorageVolumes` — a context, a DataStore, an
     * application scope — to ask where a file goes would be testing Hilt.
     */
    @Binds
    @Singleton
    fun bindsDownloadRoots(impl: StorageVolumes): DownloadRoots
}
