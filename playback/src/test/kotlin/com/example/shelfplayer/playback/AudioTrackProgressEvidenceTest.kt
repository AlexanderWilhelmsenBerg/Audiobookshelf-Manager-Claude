package com.example.shelfplayer.playback

import androidx.media3.common.Player
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioTrackProgressEvidenceTest {
    private val evidence = AudioTrackProgressEvidence()
    private val playing = AudioTrackPlaybackSnapshot(true, Player.STATE_READY, 0, false, 100, 5_000, false, 1, 0)

    @Test
    fun `healthy frame consumption records progress without repeating it`() {
        assertEquals(AudioTrackProgressEvidence.Kind.Baseline, observe(0, 100)?.kind)
        assertEquals(AudioTrackProgressEvidence.Kind.Advancing, observe(250, 200)?.kind)
        assertNull(observe(500, 300))
        assertNull(observe(2_000, 400))
    }

    @Test
    fun `ready buffering and native pause oscillations cannot hide a stationary head`() {
        observe(0, 100)
        assertNull(observe(750, 100, playing.copy(state = Player.STATE_BUFFERING), native = 2))
        assertNull(observe(1_500, 100))
        val stalled = observe(2_000, 100, playing.copy(state = Player.STATE_BUFFERING))
        assertEquals(AudioTrackProgressEvidence.Kind.NoFrameProgress, stalled?.kind)
        assertEquals(2_000L, stalled?.stationaryMs)
        assertNull(observe(3_000, 100))
        assertEquals(AudioTrackProgressEvidence.Kind.Advancing, observe(3_250, 120)?.kind)
    }

    @Test
    fun `listener pause suppression mute and idle do not report a stall`() {
        for (snapshot in listOf(
            playing.copy(playWhenReady = false),
            playing.copy(suppression = 1),
            playing.copy(muted = true),
            playing.copy(state = Player.STATE_IDLE),
            null,
        )) {
            observe(0, 100)
            assertNull(observe(5_000, 100, snapshot))
            assertEquals(AudioTrackProgressEvidence.Kind.Baseline, observe(6_000, 100)?.kind)
        }
    }

    @Test
    fun `new track generation selection capture or comparison mode starts fresh evidence`() {
        observe(0, 100)
        assertEquals(AudioTrackProgressEvidence.Kind.Baseline, observe(3_000, 0, epoch = 2)?.kind)
        assertEquals(AudioTrackProgressEvidence.Kind.Baseline, observe(6_000, 0, playing.copy(generation = 2))?.kind)
        assertEquals(AudioTrackProgressEvidence.Kind.Baseline, observe(9_000, 0, playing.copy(selection = 1))?.kind)
        assertEquals(AudioTrackProgressEvidence.Kind.Baseline, observe(12_000, 0, capture = 2)?.kind)
        assertEquals(
            AudioTrackProgressEvidence.Kind.Baseline,
            observe(15_000, 0, playing.copy(automaticTakeBack = false))?.kind,
        )
    }

    @Test
    fun `counter reset unknown counter and backwards time never become a giant delta`() {
        observe(1_000, 100)
        assertEquals(AudioTrackProgressEvidence.Kind.CounterReset, observe(2_000, 10)?.kind)
        assertNull(observe(3_000, null))
        assertEquals(AudioTrackProgressEvidence.Kind.Baseline, observe(4_000, 10)?.kind)
        assertEquals(AudioTrackProgressEvidence.Kind.Baseline, observe(500, 10)?.kind)
    }

    private fun observe(
        ms: Long,
        head: Long?,
        snapshot: AudioTrackPlaybackSnapshot? = playing,
        epoch: Long = 1,
        capture: Long = 1,
        native: Int = 3,
    ) = evidence.observe(capture, sample(ms, head, epoch, native), snapshot)

    private fun sample(ms: Long, head: Long?, epoch: Long, native: Int) =
        AudioTrackTimingSample(epoch, ms * 1_000_000, native, 22_050, 2, 2, 11_025, head, null, null, 0, 1_000, 1_000)
}
