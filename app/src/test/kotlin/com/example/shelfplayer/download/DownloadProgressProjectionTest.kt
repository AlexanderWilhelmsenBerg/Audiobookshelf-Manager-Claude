package com.example.shelfplayer.download

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.DownloadProgress
import com.example.shelfplayer.domain.download.DownloadRecoveryState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DownloadProgressProjectionTest {

    @Test
    fun `worker progress data preserves truthful bytes total and percent`() {
        val data = BookDownloadWorker.progressData(
            DownloadProgress(downloadedBytes = 256L, totalBytes = 1_024L, fraction = 0.25f),
        )

        assertEquals(256L, data.getLong(BookDownloadWorker.KEY_PROGRESS_BYTES, -1L))
        assertEquals(1_024L, data.getLong(BookDownloadWorker.KEY_PROGRESS_TOTAL_BYTES, -1L))
        assertEquals(25, data.getInt(BookDownloadWorker.KEY_PROGRESS_PERCENT, -1))
    }

    @Test
    fun `independent physical downloads use independent notification identities`() {
        val factory = DownloadNotificationFactory(ApplicationProvider.getApplicationContext())
        val first = factory.notificationId(ServerId("srv-a"), LibraryItemId("book-a"))
        val second = factory.notificationId(ServerId("srv-a"), LibraryItemId("book-b"))

        assertNotEquals(first, second)
    }

    @Test
    fun `notification copy is generic and never exposes physical identity`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = DownloadNotificationFactory(context)
        val server = ServerId("secret-server")
        val item = LibraryItemId("secret-book")
        val notification = factory.notification(
            serverId = server,
            itemId = item,
            state = DownloadRecoveryState.Waiting,
            progress = DownloadProgress(downloadedBytes = 512L, totalBytes = 1_024L, fraction = 0.5f),
        )

        val visible = listOf(
            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
        ).joinToString(" ")
        assertTrue(visible.contains("Waiting for an allowed network"))
        assertFalse(visible.contains(server.value))
        assertFalse(visible.contains(item.value))
    }
}
