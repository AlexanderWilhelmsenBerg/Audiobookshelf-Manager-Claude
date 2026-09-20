package com.example.shelfplayer.auto

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.domain.repository.ProfileRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * Android Auto's host process cannot read BookWave's app-private Coil files directly.
 *
 * This exported provider is intentionally capability-only: callers cannot list, query, insert, update or
 * delete anything. They can open only an unguessable SHA-256 token BookWave already placed in media metadata.
 * The token resolves to a process-local source entry, and that entry is refused once another profile becomes
 * active. Server URLs and credentials are therefore never exposed in the content URI.
 */
class AutoArtworkContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != READ_MODE) throw FileNotFoundException("Android Auto artwork is read-only")
        val token = uri.lastPathSegment?.takeIf(AutoArtworkRegistry::isToken)
            ?: throw FileNotFoundException("Invalid artwork capability")
        val entry = AutoArtworkRegistry.resolve(token)
            ?: throw FileNotFoundException("Artwork capability is no longer active")
        val appContext = context?.applicationContext ?: throw FileNotFoundException("No application context")
        val graph = EntryPointAccessors.fromApplication(appContext, AutoArtworkEntryPoint::class.java)

        val active = runBlocking(graph.ioDispatcher()) { graph.profiles().activeProfileId() }
        if (active != entry.profileId) throw FileNotFoundException("Artwork belongs to another profile")

        val directory = File(appContext.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
        val target = File(directory, "$token.png")
        if (!target.isFile || target.length() == 0L) {
            materialize(graph.imageLoader(), graph.ioDispatcher(), appContext, entry.sources, target)
        }
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun materialize(
        imageLoader: ImageLoader,
        ioDispatcher: CoroutineDispatcher,
        context: android.content.Context,
        sources: List<String>,
        target: File,
    ) {
        val success = runBlocking(ioDispatcher) {
            sources.firstNotNullOfOrNull { source ->
                val result = imageLoader.execute(
                    ImageRequest.Builder(context)
                        .data(source)
                        .size(MAX_ARTWORK_PIXELS)
                        .allowHardware(false)
                        .networkCachePolicy(CachePolicy.DISABLED)
                        .build(),
                )
                (result as? SuccessResult)?.drawable
            }
        } ?: throw FileNotFoundException("Artwork is not cached or reachable")

        val temporary = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temporary).use { output ->
            if (!success.toBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, PNG_QUALITY, output)) {
                throw FileNotFoundException("Artwork could not be transcoded")
            }
        }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }

    override fun getType(uri: Uri): String = IMAGE_PNG

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        0

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface AutoArtworkEntryPoint {
        fun imageLoader(): ImageLoader

        fun profiles(): ProfileRepository

        @Dispatcher(ShelfDispatcher.Io)
        fun ioDispatcher(): CoroutineDispatcher
    }

    private companion object {
        const val READ_MODE = "r"
        const val CACHE_DIRECTORY = "auto-art"
        const val MAX_ARTWORK_PIXELS = 512
        const val PNG_QUALITY = 100
        const val IMAGE_PNG = "image/png"
    }
}
