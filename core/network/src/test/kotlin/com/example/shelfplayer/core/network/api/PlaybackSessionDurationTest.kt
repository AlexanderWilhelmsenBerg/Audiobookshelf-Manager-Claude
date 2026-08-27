package com.example.shelfplayer.core.network.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC PLAY-003 / 22.4 / 22.5, `docs/risks.md` R-61 — the arithmetic `TrackDurations` rests on.
 *
 * ### Why this test exists
 *
 * `TrackDurations.recovered` fills in a track whose length the server did not give, by subtracting the known
 * tracks from the session's own `duration`. That is only correct if a session's `duration` **is** the sum of
 * its `audioTracks` durations — which is a claim about the server, so it is pinned against the captured
 * fixtures rather than asserted in prose.
 *
 * `CapturedShapesTest` already pins the equivalent for a *library item*'s `media` block. This pins it for the
 * play endpoint, which is the shape `PlaybackMapper` actually reads and the one the recovery consumes.
 *
 * ### And the limit it pins, which matters as much
 *
 * The last test records that **no captured session has an excluded track**. That is why
 * `TrackDurations.recovered` refuses to recover when one is present: whether `duration` counts an excluded
 * file's length is not established, and if it does, the recovered track would come out too long. When a
 * capture finally contains an excluded track, this test starts failing — which is the intended way to be
 * told that the refusal can be revisited (22.4).
 */
class PlaybackSessionDurationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val fixtures = listOf("item-play", "multi-item-play")

    /**
     * The identity the recovery subtracts against, on every captured session.
     *
     * Asserted to the millisecond rather than to a tolerance: the server accumulates these itself — the
     * `startOffset` chain in `CapturedShapesTest` is the same accumulation seen from the other side — so an
     * inexact match would mean the model is wrong, not that floating point drifted.
     */
    @Test
    fun `a session's duration is the sum of its audio tracks`() {
        for (fixture in fixtures) {
            val body = bodyOf(fixture)
            val tracks = body.getValue("audioTracks").jsonArray.map { it.jsonObject }

            val summed = tracks.sumOf { it.getValue("duration").jsonPrimitive.double }

            assertEquals(
                body.getValue("duration").jsonPrimitive.double,
                summed,
                "$fixture: TrackDurations subtracts against this identity",
            )
        }
    }

    /** A session reports at least one track, or there is nothing for the identity to be about. */
    @Test
    fun `every captured session has tracks with positive durations`() {
        for (fixture in fixtures) {
            val tracks = bodyOf(fixture).getValue("audioTracks").jsonArray.map { it.jsonObject }

            assertTrue(tracks.isNotEmpty(), "$fixture has no audioTracks")
            for (track in tracks) {
                assertTrue(
                    track.getValue("duration").jsonPrimitive.double > 0.0,
                    "$fixture carries a non-positive track duration — R-61's premise has changed",
                )
            }
        }
    }

    /**
     * **No captured session has an excluded track**, which is precisely why the recovery refuses when one
     * does.
     *
     * This is the test to read when somebody wonders whether `TrackDurations`' `anyExcluded` guard is
     * paranoia. It is not: the guard exists because this assertion holds, so the interaction between
     * `exclude` and `duration` has never been observed. A capture containing one turns this red, and that is
     * the moment the guard may be reconsidered — with evidence.
     */
    @Test
    fun `no captured session exercises an excluded track`() {
        for (fixture in fixtures) {
            val tracks = bodyOf(fixture).getValue("audioTracks").jsonArray.map { it.jsonObject }

            assertTrue(
                tracks.none { it.getValue("exclude").jsonPrimitive.boolean },
                "$fixture now has an excluded track — TrackDurations' refusal can be revisited with it",
            )
        }
    }

    private fun bodyOf(fixture: String): JsonObject = json.parseToJsonElement(ContractFixtures.body(fixture)).jsonObject
}
