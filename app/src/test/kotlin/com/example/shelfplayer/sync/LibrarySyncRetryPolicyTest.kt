package com.example.shelfplayer.sync

import com.example.shelfplayer.core.model.AppError
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibrarySyncRetryPolicyTest {

    @Test
    fun `transient failures ask WorkManager to retry`() {
        assertTrue(shouldRetryBackgroundSync(AppError.Network()))
        assertTrue(shouldRetryBackgroundSync(AppError.Timeout()))
        assertTrue(shouldRetryBackgroundSync(AppError.Server(statusCode = 429)))
        assertTrue(shouldRetryBackgroundSync(AppError.Server(statusCode = 503)))
    }

    @Test
    fun `failures needing external intervention do not enter immediate backoff`() {
        assertFalse(shouldRetryBackgroundSync(AppError.Authentication()))
        assertFalse(shouldRetryBackgroundSync(AppError.Authorization(summary = "denied")))
        assertFalse(shouldRetryBackgroundSync(AppError.Validation(summary = "invalid")))
        assertFalse(shouldRetryBackgroundSync(AppError.ApiCompatibility(summary = "unsupported")))
        assertFalse(shouldRetryBackgroundSync(AppError.Storage(summary = "disk")))
    }
}
