package com.example.shelfplayer.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Retries a media request once after an authenticated Audiobookshelf stream returns HTTP 401.
 *
 * A fresh delegate is created for the retry because a data source that failed during `open` is not promised
 * to be reusable. The retry itself is never caught here: if the renewed credential is also refused, Media3
 * receives that honest failure rather than entering a credential loop.
 */
@OptIn(UnstableApi::class)
internal class RenewingDataSource(
    private val delegateFactory: DataSource.Factory,
    private val credentials: PlaybackCredentialRenewer,
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var delegate = newDelegate()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        delegate.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val rejectedToken = credentials.tokenFor(dataSpec.uri)
        return try {
            delegate.open(dataSpec)
        } catch (failure: IOException) {
            if (failure.httpResponseCode() != HTTP_UNAUTHORIZED ||
                !credentials.recoverAfterUnauthorized(dataSpec.uri, rejectedToken)
            ) {
                throw failure
            }
            replaceDelegate()
            delegate.open(dataSpec)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length)

    override fun getUri(): Uri? = delegate.uri

    override fun close() = delegate.close()

    private fun replaceDelegate() {
        try {
            delegate.close()
        } catch (_: IOException) {
            // The failed request is already being replaced. Its close error cannot make the replacement safer.
        }
        delegate = newDelegate()
    }

    private fun newDelegate(): DataSource = delegateFactory.createDataSource().also { created ->
        listeners.forEach(created::addTransferListener)
    }

    class Factory(
        private val delegateFactory: DataSource.Factory,
        private val credentials: PlaybackCredentialRenewer,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = RenewingDataSource(delegateFactory, credentials)
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
    }
}
