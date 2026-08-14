package com.example.shelfplayer.data.downloads.di

import com.example.shelfplayer.data.downloads.DefaultDownloadRepository
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
}
