package com.example.shelfplayer.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** PRODUCT_SPEC AUTH-004 — an active range request gets one fresh-data-source retry, never a loop. */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class RenewingDataSourceTest {
    private val dataSpec = DataSpec("https://books.example/audio/book.m4b".toUri())

    @Test
    fun `401 renewal retries once with a fresh data source`() {
        val first = FakeDataSource(openFailure = unauthorized())
        val second = FakeDataSource(openLength = 42L)
        val factory = QueueDataSourceFactory(first, second)
        val credentials = FakeCredentialRecovery(canRecover = true)
        val source = RenewingDataSource(factory, credentials)

        assertEquals(42L, source.open(dataSpec))
        assertEquals(2, factory.createdCount)
        assertEquals(1, credentials.recoveryCalls)
        assertEquals(0, credentials.rejectionCalls)
        assertEquals("access-1", credentials.rejectedTokens.single())
        assertEquals(1, first.closeCalls)
    }

    @Test
    fun `second 401 is surfaced without another renewal or third request`() {
        val factory = QueueDataSourceFactory(
            FakeDataSource(openFailure = unauthorized()),
            FakeDataSource(openFailure = unauthorized()),
        )
        val credentials = FakeCredentialRecovery(canRecover = true)
        val source = RenewingDataSource(factory, credentials)

        assertFailsWith<HttpDataSource.InvalidResponseCodeException> { source.open(dataSpec) }
        assertEquals(2, factory.createdCount)
        assertEquals(1, credentials.recoveryCalls)
        assertEquals(1, credentials.rejectionCalls)
    }

    @Test
    fun `401 rejected by credential boundary is not retried`() {
        val factory = QueueDataSourceFactory(FakeDataSource(openFailure = unauthorized()))
        val credentials = FakeCredentialRecovery(canRecover = false)
        val source = RenewingDataSource(factory, credentials)

        assertFailsWith<HttpDataSource.InvalidResponseCodeException> { source.open(dataSpec) }
        assertEquals(1, factory.createdCount)
        assertEquals(1, credentials.recoveryCalls)
    }

    private fun unauthorized() = HttpDataSource.InvalidResponseCodeException(
        401,
        "Unauthorized",
        null,
        emptyMap(),
        dataSpec,
        ByteArray(0),
    )

    private class FakeCredentialRecovery(private val canRecover: Boolean) : PlaybackCredentialRecovery {
        var recoveryCalls = 0
            private set
        var rejectionCalls = 0
            private set
        val rejectedTokens = mutableListOf<String?>()

        override fun tokenFor(uri: Uri): String = "access-1"

        override fun recoverAfterUnauthorized(uri: Uri, rejectedToken: String?): Boolean {
            recoveryCalls += 1
            rejectedTokens += rejectedToken
            return canRecover
        }

        override fun rejectRenewedCredential(uri: Uri) {
            rejectionCalls += 1
        }
    }

    private class QueueDataSourceFactory(vararg sources: FakeDataSource) : DataSource.Factory {
        private val remaining = ArrayDeque(sources.toList())
        var createdCount = 0
            private set

        override fun createDataSource(): DataSource {
            createdCount += 1
            return remaining.removeFirst()
        }
    }

    private class FakeDataSource(private val openLength: Long = 0L, private val openFailure: IOException? = null) :
        DataSource {
        var closeCalls = 0
            private set

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            openFailure?.let { throw it }
            return openLength
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1

        override fun getUri(): Uri? = null

        override fun close() {
            closeCalls += 1
        }
    }
}
