package com.example.shelfplayer.auto

import android.content.Context
import android.net.Uri
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Author
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.feature.browse.authorUrlsFor
import com.example.shelfplayer.feature.browse.coverUrlsFor
import com.example.shelfplayer.playback.AutoArtwork
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PD-001 — resolves server artwork to an opaque local capability for the external media/car host.
 *
 * No server URL or credential crosses the Media3 boundary. The actual bytes are opened by
 * [AutoArtworkContentProvider], which reuses BookWave's current Coil loader/cache and checks that the profile
 * that minted the URI is still active before serving it.
 */
@Singleton
class AndroidAutoArtwork @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val profiles: ProfileRepository,
) : AutoArtwork {

    override suspend fun book(
        book: Book,
        serverBaseUrls: Map<ServerId, String>,
        offlineCoverUri: String?,
    ): Uri? {
        val profileId = profiles.activeProfileId() ?: return null
        val sources = listOfNotNull(
            offlineCoverUri,
            coverUrlsFor(serverBaseUrls).forBook(book),
        )
        return AutoArtworkRegistry.register(context, profileId, sources)
    }

    override suspend fun author(
        author: Author,
        representativeCover: Book?,
        serverBaseUrls: Map<ServerId, String>,
        representativeOfflineCoverUri: String?,
    ): Uri? {
        val profileId = profiles.activeProfileId() ?: return null
        val sources = buildList {
            authorUrlsFor(serverBaseUrls).forAuthor(author)?.let(::add)
            representativeOfflineCoverUri?.let(::add)
            representativeCover?.let { book -> coverUrlsFor(serverBaseUrls).forBook(book) }?.let(::add)
        }.distinct()
        return AutoArtworkRegistry.register(context, profileId, sources)
    }
}

internal object AutoArtworkRegistry {
    data class Entry(val profileId: ProfileId, val sources: List<String>)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun register(context: Context, profileId: ProfileId, sources: List<String>): Uri? {
        val usable = sources.filter(String::isNotBlank).distinct()
        if (usable.isEmpty()) return null
        val token = digest(profileId, usable)
        entries[token] = Entry(profileId, usable)
        return Uri.Builder()
            .scheme("content")
            .authority("${context.packageName}.$AUTHORITY_SUFFIX")
            .appendPath(token)
            .build()
    }

    fun resolve(token: String): Entry? = entries[token]

    internal fun isToken(value: String): Boolean =
        value.length == SHA256_HEX_LENGTH && value.all { character -> character in '0'..'9' || character in 'a'..'f' }

    private fun digest(profileId: ProfileId, sources: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(profileId.value.toByteArray(Charsets.UTF_8))
        sources.forEach { source ->
            digest.update(0.toByte())
            digest.update(source.toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    const val AUTHORITY_SUFFIX = "autoart"
    private const val SHA256_HEX_LENGTH = 64
}
