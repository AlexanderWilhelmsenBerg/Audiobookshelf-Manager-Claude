package com.example.shelfplayer.playback.di

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.example.shelfplayer.core.network.di.MediaStreamingClient
import com.example.shelfplayer.playback.AudioOutputRouter
import com.example.shelfplayer.playback.AutoLibrary
import com.example.shelfplayer.playback.CarReadinessReader
import com.example.shelfplayer.playback.DefaultCarReadinessReader
import com.example.shelfplayer.playback.DefaultNotificationAccessReader
import com.example.shelfplayer.playback.DefaultPlayerFactory
import com.example.shelfplayer.playback.MediaDataSource
import com.example.shelfplayer.playback.NotificationAccessReader
import com.example.shelfplayer.playback.PlayerFactory
import com.google.common.util.concurrent.MoreExecutors
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import javax.inject.Singleton

/**
 * PRODUCT_SPEC PLAY-001 / 14.5 — how audio and artwork are fetched.
 *
 * ### The data source is the app's long-lived authenticated client
 *
 * Audiobookshelf sends track URLs as credential-free paths, so the `Authorization` header is what
 * fetches them. ExoPlayer's default HTTP stack has no such header, and the usual workaround — a token
 * in the query string — is exactly what PRODUCT_SPEC 14.5 forbids. Wrapping the app's own client is
 * both the correct answer and the smaller one: the interceptor that signs a library request signs a
 * media request, and there is no second credential path to keep in step. PRODUCT_SPEC 10.3 requires
 * endpoint-appropriate lifetimes, so this client shares the ordinary API stack but has no absolute
 * whole-call timeout; the read timeout still detects a stream that has stopped delivering bytes.
 *
 * [DefaultDataSource.Factory] wraps it rather than [OkHttpDataSource.Factory] being used alone, so that
 * a `file://` URI resolves too. That costs nothing today and is what Phase 3's downloaded books will
 * play through.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface PlaybackModule {

    @Binds
    @Singleton
    fun bindsPlayerFactory(impl: DefaultPlayerFactory): PlayerFactory

    @Binds
    @Singleton
    fun bindsNotificationAccessReader(impl: DefaultNotificationAccessReader): NotificationAccessReader

    @Binds
    @Singleton
    fun bindsCarReadinessReader(impl: DefaultCarReadinessReader): CarReadinessReader

    /**
     * PRODUCT_SPEC PLAY-002 — the browse tree sees only the three methods it needs.
     *
     * `AudioOutputRouter` is a `@Singleton` in its own right — the service injects the class to attach a
     * player to it — and this binds the narrow view `AutoLibrary` takes. Both resolve to the one instance,
     * which is what makes the tick in the phone's menu and the row marked in the car the same fact.
     */
    @Binds
    fun bindsAutoLibraryOutputs(impl: AudioOutputRouter): AutoLibrary.Outputs

    companion object {
        @Provides
        @Singleton
        @MediaDataSource
        @OptIn(UnstableApi::class)
        fun providesMediaDataSourceFactory(
            @ApplicationContext context: Context,
            @MediaStreamingClient client: OkHttpClient,
        ): DataSource.Factory = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(client))

        /**
         * PRODUCT_SPEC LIB-004 — the notification's cover art, over the same authenticated stack.
         *
         * Media3's default bitmap loader uses its own HTTP stack, which would fetch a cover with no
         * credential. The capture server answers `200` to that, and a deployment behind forward auth
         * answers `401` — leaving a lock screen with no artwork and no explanation. The same reasoning
         * as `ImageModule`'s, applied to the one image the app shows outside its own window.
         *
         * A single thread: artwork is loaded once per book, and Media3 caches the result.
         */
        @Provides
        @Singleton
        @OptIn(UnstableApi::class)
        fun providesBitmapLoader(
            @ApplicationContext context: Context,
            @MediaDataSource dataSourceFactory: DataSource.Factory,
        ): BitmapLoader = // Media3 1.11.0 deprecated every `DataSourceBitmapLoader` constructor in favour of a builder,
            // which is why this takes a `Context` it does not otherwise need — the builder requires one.
            // The two things that matter are unchanged and set explicitly: the authenticated data source
            // factory, and a single-threaded executor.
            DataSourceBitmapLoader.Builder(context)
                .setDataSourceFactory(dataSourceFactory)
                .setExecutorService(MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()))
                .build()
    }
}
