package com.example.shelfplayer.core.network.api

import kotlinx.serialization.json.Json
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
     * **Un-finishing is not settled, and this test says so rather than pretending otherwise.**
     *
     * The probe sent `{"currentTime": 42.5, "isFinished": false}` to a book eight seconds long and read
     * back `isFinished: true`. Two explanations fit and the capture cannot separate them: the server may
     * have rejected a position past the duration outright, or it may re-derive `isFinished` from a clamped
     * progress of 1 and ignore the flag it was given.
     *
     * Either way the app's *Finished* checkbox may not un-tick on the server, which is a real risk to
     * PLAY-004's "marking finished is explicit, in both directions" and is recorded as such in
     * `docs/api-compatibility.md`. `scripts/capture-contracts.sh` now sends a position **inside** the
     * duration and captures the PATCH responses themselves, so the next run separates the two.
     *
     * This test asserts what was observed — not what the app hopes. When a better capture lands it will
     * fail, and failing is the correct behaviour: it is the reminder that the question was open.
     */
    @Test
    fun `un-finishing a book is unconfirmed and the fixture records why`() {
        val after = json.parseToJsonElement(ContractFixtures.body("media-progress-unfinished")).jsonObject

        assertTrue(
            after.getValue("isFinished").jsonPrimitive.content.toBoolean(),
            "the 2026-08-13 probe did not un-finish the book; if this now passes as false, the question " +
                "is settled and docs/api-compatibility.md needs updating",
        )
    }

    private fun rawEnvelope(fixture: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("contracts/$fixture.json")) {
            "no committed fixture named contracts/$fixture.json. Run scripts/capture-contracts.sh."
        }
        return stream.use { it.readBytes().decodeToString() }
    }
}
