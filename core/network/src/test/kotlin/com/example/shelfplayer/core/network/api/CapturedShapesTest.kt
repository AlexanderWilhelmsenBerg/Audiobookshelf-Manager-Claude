package com.example.shelfplayer.core.network.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC 22.4 / 22.5 — shapes a capture has settled, pinned before anything is built on them.
 *
 * ### Why a test with no adapter behind it
 *
 * 22.4 and 22.5 say the app may not be written against a response shape no capture has produced. That
 * rule has a corollary nobody writes down: once a capture *has* produced one, the observation has to be
 * load-bearing, or it decays into a memory of a fixture somebody looked at once. These assertions are
 * what turn a recorded file into a fact the build defends.
 *
 * Two groups.
 *
 * **Bookmarks** (PRODUCT_SPEC 11.1) have been the top unbuilt Phase 2 recommendation since wave 5's
 * closeout, blocked on exactly this: nothing in the app knew what a bookmark looked like, and the player
 * has carried a disabled button since wave 2. The 2026-08-13 capture answers all four questions — the
 * create response, where a bookmark is *stored*, what an update returns, and what a delete returns — so
 * the feature is now buildable and the shape it will be built against is fixed here.
 *
 * **The finished flag** (PLAY-004) is the more interesting one, because the capture came back with an
 * answer nobody expected. See below.
 */
class CapturedShapesTest {

    private val json = Json { ignoreUnknownKeys = true }

    // --- Bookmarks (PRODUCT_SPEC 11.1) ------------------------------------------------------------

    /**
     * `POST /api/me/item/{id}/bookmark` returns the bookmark it made.
     *
     * Four fields and no id: a bookmark is identified by its **time**, which is why the delete route ends
     * in a number of seconds rather than in a UUID. That is the single most surprising thing about this
     * API and the one a client written from memory would get wrong.
     */
    @Test
    fun `creating a bookmark returns the bookmark, keyed by its time`() {
        val created = json.parseToJsonElement(ContractFixtures.body("bookmark-create")).jsonObject

        assertEquals(setOf("createdAt", "libraryItemId", "time", "title"), created.keys)
        assertEquals(31, created.getValue("time").jsonPrimitive.content.toInt())
    }

    /**
     * A bookmark lives on the **user**, not on the item.
     *
     * `GET /api/me` carries `bookmarks` as a flat array across every book, so a client that wants one
     * book's bookmarks filters by `libraryItemId` itself. No endpoint states this; only reading `me` back
     * after a create does, which is why the capture does exactly that.
     */
    @Test
    fun `a bookmark is stored on the user object`() {
        val me = json.parseToJsonElement(ContractFixtures.body("me-with-bookmark")).jsonObject
        val bookmarks = assertNotNull(me["bookmarks"], "me carries no bookmarks array").jsonArray

        assertEquals(1, bookmarks.size)
        assertEquals("A line worth keeping", bookmarks.single().jsonObject.getValue("title").jsonPrimitive.content)
    }

    /** `PATCH` returns the whole bookmark, so a client can render the result rather than re-reading `me`. */
    @Test
    fun `updating a bookmark returns the updated bookmark`() {
        val updated = json.parseToJsonElement(ContractFixtures.body("bookmark-update")).jsonObject

        assertEquals("A line worth keeping, renamed", updated.getValue("title").jsonPrimitive.content)
        assertEquals(31, updated.getValue("time").jsonPrimitive.content.toInt(), "the time is the key and is kept")
    }

    /**
     * `DELETE` answers `200` with the plain text `OK` — not JSON, and not `204`.
     *
     * Worth pinning because a client that assumes a JSON body throws on the successful case, which is the
     * kind of defect that only shows up the first time a user deletes something.
     */
    @Test
    fun `deleting a bookmark answers with plain text`() {
        val envelope = json.parseToJsonElement(rawEnvelope("bookmark-delete")).jsonObject

        assertEquals(200, envelope.getValue("status").jsonPrimitive.content.toInt())
        assertEquals("text", envelope.getValue("bodyKind").jsonPrimitive.content)
        assertEquals("OK", envelope.getValue("bodyText").jsonPrimitive.content)
    }

    // --- The finished flag, and what the capture actually settled (PLAY-004) ----------------------

    /**
     * `isFinished: true` round-trips, and the server **zeroes the position** when it does.
     *
     * The app sends the end of the book when a listener ticks *Finished* (see `DefaultPlaybackRepository`),
     * and what comes back is `currentTime: 8` — the duration — with `progress: 1`. So the server accepts the
     * position it was given and derives `progress` from it. Before this capture, `true` was a value the app
     * sent that no fixture had ever seen come back.
     */
    @Test
    fun `marking a book finished round-trips`() {
        val finished = json.parseToJsonElement(ContractFixtures.body("media-progress-finished")).jsonObject

        assertTrue(finished.getValue("isFinished").jsonPrimitive.content.toBoolean())
        assertEquals(1, finished.getValue("progress").jsonPrimitive.content.toDouble().toInt())
        assertEquals(
            finished.getValue("duration").jsonPrimitive.content,
            finished.getValue("currentTime").jsonPrimitive.content,
            "a finished book sits at its end",
        )
    }

    /**
     * **Un-finishing is accepted, and then overruled by the server's own threshold.**
     *
     * The un-finish PATCH answers `200 OK` — so the request is not rejected, which was the worrying of the
     * two possibilities. What happens next is the server's `markAsFinishedTimeRemaining` rule, and the CI
     * server log names it in as many words:
     *
     * ```
     * [MediaProgress] Marking media progress as finished because time remaining (8)
     *                 is less than 10 seconds
     * ```
     *
     * The contract book is **eight seconds long**, so *every* position in it is within ten seconds of the
     * end and it can never be anything but finished. That is a property of the fixture, not of the API: a
     * real thirty-hour book at two seconds is nowhere near its last ten.
     *
     * So this asserts both halves — the acceptance, which is the contract, and the fixture's own
     * inescapable finishedness, which is why the fixture cannot demonstrate the rest. Testing un-finishing
     * properly needs a seeded book longer than the threshold; that belongs with the
     * `markAsFinishedTimeRemaining` work, which has to read the setting anyway.
     */
    @Test
    fun `un-finishing is accepted, and then re-finished by the ten-second rule`() {
        val patch = json.parseToJsonElement(rawEnvelope("media-progress-set-unfinished")).jsonObject
        val after = json.parseToJsonElement(ContractFixtures.body("media-progress-unfinished")).jsonObject

        assertEquals(200, patch.getValue("status").jsonPrimitive.content.toInt(), "the un-finish is accepted")
        assertTrue(
            after.getValue("isFinished").jsonPrimitive.content.toBoolean(),
            "and an eight-second book is always within ten seconds of its end",
        )
    }

    /**
     * PRODUCT_SPEC PLAY-004 / ADR-0013 — the library's own finished rule, in the capture that has always had it.
     *
     * `libraries.json` has carried `settings.markAsFinishedTimeRemaining: 10` since the wave A capture, and
     * the app parsed it away until the Phase 2 closeout. The plan for PR 2 opened by naming a new capture as
     * its blocker; reading the committed fixture showed there was nothing to capture. This assertion is what
     * stops that gap reopening: the two fields the app now depends on are pinned to the observation, and a
     * server version that stopped sending them would fail the build rather than quietly restore a constant.
     *
     * `markAsFinishedPercentComplete` is asserted **present and null**, which is the honest state of that
     * branch — implemented against the documented field, never observed with a value (PRODUCT_SPEC 22.5).
     */
    @Test
    fun `a library carries its own finished rule`() {
        val libraries = json.parseToJsonElement(ContractFixtures.body("libraries")).jsonObject
            .getValue("libraries").jsonArray
        assertTrue(libraries.isNotEmpty(), "the capture has at least one library")

        libraries.forEach { library ->
            val settings = assertNotNull(library.jsonObject["settings"]).jsonObject
            assertEquals(10, settings.getValue("markAsFinishedTimeRemaining").jsonPrimitive.content.toInt())
            assertTrue(
                settings.containsKey("markAsFinishedPercentComplete"),
                "the percentage field is sent, so the branch is written to a real field",
            )
            assertEquals(
                null,
                settings.getValue("markAsFinishedPercentComplete").jsonPrimitive.contentOrNull,
                "and no capture has ever given it a value",
            )
        }
    }

    /** Both progress writes answer plain text, like the bookmark delete and unlike everything else. */
    @Test
    fun `a progress patch answers with plain text`() {
        listOf("media-progress-set-finished", "media-progress-set-unfinished").forEach { name ->
            val envelope = json.parseToJsonElement(rawEnvelope(name)).jsonObject

            assertEquals(200, envelope.getValue("status").jsonPrimitive.content.toInt(), name)
            assertEquals("OK", envelope.getValue("bodyText").jsonPrimitive.content, name)
        }
    }

    private fun rawEnvelope(fixture: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("contracts/$fixture.json")) {
            "no committed fixture named contracts/$fixture.json. Run scripts/capture-contracts.sh."
        }
        return stream.use { it.readBytes().decodeToString() }
    }
}
