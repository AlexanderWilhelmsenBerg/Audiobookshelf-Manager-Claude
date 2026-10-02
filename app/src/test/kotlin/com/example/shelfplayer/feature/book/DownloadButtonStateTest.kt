package com.example.shelfplayer.feature.book

import org.junit.Test
import kotlin.test.assertEquals

/**
 * #202 — while a Download tap is pending, the button says so; the manifest's evidence always wins.
 *
 * Evidence winning is the property that matters: the pending flag is the screen's guess, the manifest is
 * what is actually on the device, and the guess must never hide a download that is already arriving or here.
 */
class DownloadButtonStateTest {

    @Test
    fun `without a pending tap every state is shown as the manifest has it`() {
        val states = listOf(
            DownloadButtonState.NotDownloaded,
            DownloadButtonState.OnDevice,
            DownloadButtonState.Downloading(progress = null),
            DownloadButtonState.Downloading(progress = 0.4f),
            DownloadButtonState.Downloaded,
            DownloadButtonState.Failed,
        )

        states.forEach { state -> assertEquals(state, state.whileStarting(isStarting = false)) }
    }

    @Test
    fun `a pending tap is shown at once over every state a tap can start from`() {
        listOf(DownloadButtonState.NotDownloaded, DownloadButtonState.OnDevice, DownloadButtonState.Failed)
            .forEach { tapped ->
                assertEquals(DownloadButtonState.Starting, tapped.whileStarting(isStarting = true), "$tapped")
            }
    }

    @Test
    fun `the manifest's evidence of a download wins over a pending tap`() {
        val arriving = DownloadButtonState.Downloading(progress = 0.4f)
        val firstByte = DownloadButtonState.Downloading(progress = null)

        assertEquals(arriving, arriving.whileStarting(isStarting = true))
        assertEquals(firstByte, firstByte.whileStarting(isStarting = true))
        assertEquals(DownloadButtonState.Downloaded, DownloadButtonState.Downloaded.whileStarting(isStarting = true))
    }
}
