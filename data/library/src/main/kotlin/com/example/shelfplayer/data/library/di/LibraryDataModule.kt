package com.example.shelfplayer.data.library.di

import com.example.shelfplayer.data.library.DefaultLibraryRepository
import com.example.shelfplayer.data.library.DefaultProfileRepository
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
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
    fun bindsLibraryRepository(impl: DefaultLibraryRepository): LibraryRepository

    @Binds
    @Singleton
    fun bindsProfileRepository(impl: DefaultProfileRepository): ProfileRepository
}
