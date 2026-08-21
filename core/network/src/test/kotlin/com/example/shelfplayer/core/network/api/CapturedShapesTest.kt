package com.example.shelfplayer.core.network.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
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
     * stops that gap reopening: the field the app inherits its rule from is pinned to the observation, and a
     * server version that stopped sending it would fail the build rather than quietly restore a constant.
     *
     * The second half asserts a **deliberate omission**. `markAsFinishedPercentComplete` is sent, it is
     * `null` in every capture ever taken, and this app does not read it: the project owner rejected the unit,
     * because a percentage of a long book is a long time and 95% of a hundred-hour book leaves five hours to
     * go. Pinning "sent, and not read" is what stops a later reader finding the field in the fixture and
     * concluding the app forgot it.
     */
    @Test
    fun `a library carries its own finished rule, and a percentage the app does not read`() {
        val libraries = json.parseToJsonElement(ContractFixtures.body("libraries")).jsonObject
            .getValue("libraries").jsonArray
        assertTrue(libraries.isNotEmpty(), "the capture has at least one library")

        libraries.forEach { library ->
            val settings = assertNotNull(library.jsonObject["settings"]).jsonObject
            assertEquals(10, settings.getValue("markAsFinishedTimeRemaining").jsonPrimitive.content.toInt())
            assertTrue(settings.containsKey("markAsFinishedPercentComplete"), "the percentage is sent")
            assertEquals(
                null,
                settings.getValue("markAsFinishedPercentComplete").jsonPrimitive.contentOrNull,
                "null in every capture, and unread either way — see ADR-0013",
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

    // --- The audio file endpoint (PRODUCT_SPEC DL-001, DL-002, SYNC-001) --------------------------

    /**
     * `GET /api/items/{id}/file/{fileId}` honours a `Range` request, which is what makes a resumed
     * download possible at all (Phase 3 slice 6, ADR-0018 decision 2).
     *
     * The alternative — restarting a file from zero after a dropped connection — is correct but wasteful, and
     * on a long book over a metered link it is the difference between finishing and not. Pinned here because
     * the downloader will be written assuming 206, and a server that stopped honouring it would otherwise show
     * up as duplicated bytes rather than as a failed test.
     */
    @Test
    fun `the file endpoint answers range requests`() {
        val file = json.parseToJsonElement(rawEnvelope("item-file")).jsonObject

        assertEquals("bytes", file.getValue("acceptRanges").jsonPrimitive.content)
        val range = file.getValue("range").jsonObject
        assertEquals(206, range.getValue("status").jsonPrimitive.content.toInt())
        assertTrue(range.getValue("hasContentRange").jsonPrimitive.content.toBoolean(), "a Content-Range came back")
        assertTrue(
            range.getValue("returnedRequestedLength").jsonPrimitive.content.toBoolean(),
            "1024 bytes were asked for and 1024 came back, so the range was honoured rather than ignored",
        )
    }

    /**
     * The server sends `ETag` and `Last-Modified` — a **validator**, which is not a checksum.
     *
     * The distinction decides what the *Repair* action in Phase 3 may claim. An ETag is only guaranteed to
     * change when the file changes; nothing requires it to be a hash of the bytes, and Audiobookshelf does not
     * document how it derives one. So a comparison answers *"your copy is stale"* and cannot answer *"your
     * bytes are intact"*. Integrity of what was downloaded comes from hashing it locally before it is
     * committed (DL-002 criterion 1).
     *
     * It is also what makes a resume safe: `If-Range` with this value turns "the file changed mid-download"
     * into a plain 200 that starts over, instead of half an old file joined to half a new one.
     */
    @Test
    fun `the file endpoint sends validators, and a length to resume against`() {
        val file = json.parseToJsonElement(rawEnvelope("item-file")).jsonObject
        val validators = file.getValue("validators").jsonObject

        assertTrue(validators.getValue("hasETag").jsonPrimitive.content.toBoolean(), "an ETag is sent")
        assertTrue(
            validators.getValue("hasLastModified").jsonPrimitive.content.toBoolean(),
            "and a Last-Modified, which is the fallback when a server omits the ETag",
        )
        assertTrue(file.getValue("hasContentLength").jsonPrimitive.content.toBoolean(), "and a total to resume against")
    }

    /**
     * PRODUCT_SPEC 5.1 — the audio itself is behind authentication, not merely the metadata.
     *
     * Worth pinning rather than assuming: a server that served media anonymously would make every download URL
     * a public link to the owner's library, and the app would have no way to notice.
     */
    @Test
    fun `the file endpoint refuses an unauthenticated request`() {
        val file = json.parseToJsonElement(rawEnvelope("item-file")).jsonObject

        assertEquals(401, file.getValue("unauthenticatedStatus").jsonPrimitive.content.toInt())
    }

    // --- Management (EPIC MGR / EPIC USER) --------------------------------------------------------

    /**
     * **`GET /api/users` returns every user's `token`.**
     *
     * This is the single most important thing the management captures found. The response carries a live
     * API credential *for other accounts* to any client an admin signs in with — not a hash, not a
     * placeholder, the token itself. The fixture shows `<redacted-secret>` because the capture script
     * redacts it; the wire does not.
     *
     * USER-001 says tokens are never *displayed*. The stronger rule this forces is that the app must never
     * **model** the field: no DTO property, so there is nothing to store, nothing to log, and nothing to
     * put on a screen by accident. A field that is never parsed cannot leak.
     *
     * Pinned as a test so that a future reader who wonders why `UserDto` has no `token` finds the reason
     * rather than adding one.
     */
    @Test
    fun `the user list carries a live token for every account`() {
        val users = json.parseToJsonElement(bodyOf("users-list")).jsonObject
            .getValue("users").jsonArray

        assertTrue(users.isNotEmpty())
        users.forEach { user ->
            assertTrue("token" in user.jsonObject, "a user object with no token would be the safe shape")
        }
    }

    /**
     * PRODUCT_SPEC USER-002 — a user created through this endpoint arrives **inactive**.
     *
     * The request sent a username, a password and a type, and the server answered with `isActive: false`.
     * So "create a user" does not produce an account that can sign in, and an app that reported success
     * without saying so would leave an admin waiting for somebody to log in with credentials that cannot
     * work yet.
     */
    @Test
    fun `a created user is not active`() {
        val user = json.parseToJsonElement(bodyOf("user-create")).jsonObject.getValue("user").jsonObject

        assertEquals(false, user.getValue("isActive").jsonPrimitive.content.toBoolean())
    }

    /**
     * PRODUCT_SPEC MGR-001 — the metadata `PATCH` answers with the whole updated item.
     *
     * That settles how MGR-001's *"On success, Room updates immediately and then refreshes from server"* is
     * satisfied: the refresh is the response, not a second `GET`. A client that re-fetched would be making
     * a request for data it already had, and would have a window in which the two disagreed.
     */
    @Test
    fun `updating metadata returns the updated library item`() {
        val body = json.parseToJsonElement(bodyOf("item-update")).jsonObject

        assertTrue("libraryItem" in body, "the PATCH response is the item, so no follow-up GET is needed")
    }

    /**
     * Three management endpoints answer `text/plain`, not JSON.
     *
     * `OK` is the whole body. A client that assumed every 2xx carried JSON would fail to parse a success
     * and report a failure for an operation that worked — which for the deletion would mean telling
     * somebody their book is still there when it is gone.
     */
    @Test
    fun `cover removal, library scan and item deletion answer in plain text`() {
        listOf("item-cover-remove", "library-scan", "item-delete").forEach { fixture ->
            val envelope = json.parseToJsonElement(rawEnvelope(fixture)).jsonObject
            assertEquals(200, envelope.getValue("status").jsonPrimitive.content.toInt(), fixture)
            assertTrue(
                envelope.getValue("contentType").jsonPrimitive.content.startsWith("text/plain"),
                "$fixture answered ${envelope.getValue("contentType")}",
            )
        }
    }

    /**
     * PRODUCT_SPEC MGR-005 — the deletion is confirmed by the item being gone, not by the response.
     *
     * `DELETE` answers `OK` and the item then `404`s. MGR-005 requires the local row to be removed *only
     * after server confirmation*, and this pair is what confirmation looks like on this server: a plain-text
     * acknowledgement plus an item that no longer resolves.
     */
    @Test
    fun `a deleted item stops resolving`() {
        val after = json.parseToJsonElement(rawEnvelope("item-after-delete")).jsonObject

        assertEquals(404, after.getValue("status").jsonPrimitive.content.toInt())
    }

    /**
     * PRODUCT_SPEC MGR-004 — an item scan answers with a *result*, synchronously.
     *
     * `{"result": "UPTODATE"}`. No job id, nothing to poll. MGR-004 asks for "started, running if
     * detectable, completed, and failed" — on this server an item scan has no detectable running state
     * because it is over by the time the response arrives, while a **library** scan answers `OK`
     * immediately and runs on. The two need different UI, and that is not something either response
     * announces.
     */
    @Test
    fun `an item scan answers with a result and a library scan does not`() {
        val scan = json.parseToJsonElement(bodyOf("item-scan")).jsonObject

        assertTrue("result" in scan, "the item scan is synchronous and says what it concluded")

        val library = json.parseToJsonElement(rawEnvelope("library-scan")).jsonObject
        assertTrue(
            library.getValue("contentType").jsonPrimitive.content.startsWith("text/plain"),
            "the library scan only acknowledges; it does not report a result",
        )
    }

    /**
     * PRODUCT_SPEC MGR-003 — a quick match with no provider defaults to Google, and can find nothing.
     *
     * The capture is the **no-match** shape: `{"warning": "No google match found"}`. What a *successful*
     * match returns is still unknown, because this container has no provider key and nothing to match
     * against — so MGR-003's "user sees provider, candidate title, author, year, cover, and fields that
     * will change" cannot be built from this capture alone. Recorded so that limitation is visible rather
     * than discovered halfway through the slice.
     */
    @Test
    fun `a quick match that finds nothing reports a warning rather than failing`() {
        val envelope = json.parseToJsonElement(rawEnvelope("item-match")).jsonObject
        assertEquals(200, envelope.getValue("status").jsonPrimitive.content.toInt())

        val body = json.parseToJsonElement(bodyOf("item-match")).jsonObject
        assertTrue("warning" in body, "a miss is a 200 with a warning, not an error status")
    }

    /**
     * PRODUCT_SPEC MGR-003 / 22.5 — the provider probe's fixture, which arrived one CI run after the probe.
     *
     * `AbsCapabilityResolver` was written from the Audiobookshelf project's source and shipped ahead of
     * this file, failing closed on anything it did not recognise. This is the evidence that made that safe
     * retroactively: the shape the code assumes is the shape a real 2.36.0 answered with.
     *
     * The bar the probe applies is asserted too — a provider needs a `value`, because that is what a
     * search request sends. `text` is a display name and a provider without one would still work.
     */
    @Test
    fun `the provider list names providers that a search can actually use`() {
        val providers = json.parseToJsonElement(bodyOf("search-providers"))
            .jsonObject.getValue("providers").jsonObject.getValue("books").jsonArray

        assertTrue(providers.isNotEmpty())
        assertTrue(providers.all { !it.jsonObject["value"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() })
        // Google is the default and needs no configuration, which is what makes the probe meaningful on a
        // server whose administrator has set nothing up.
        assertTrue(providers.any { it.jsonObject["value"]?.jsonPrimitive?.contentOrNull == "google" })
    }

    /**
     * PRODUCT_SPEC USER-002 — `isActive` is honoured on creation, and defaults to `false` when omitted.
     *
     * `user-create.json` omits it and comes back inactive; this one sends `true` and comes back active. The
     * pair is the finding: a client that creates a user without saying so has created an account nobody can
     * sign in to, and USER-002 cannot report "created" and stop.
     */
    @Test
    fun `a created user is active only when the request said so`() {
        val omitted = json.parseToJsonElement(bodyOf("user-create")).jsonObject.getValue("user").jsonObject
        val requested = json.parseToJsonElement(bodyOf("user-create-active"))
            .jsonObject.getValue("user").jsonObject

        assertEquals(false, omitted.getValue("isActive").jsonPrimitive.boolean)
        assertEquals(true, requested.getValue("isActive").jsonPrimitive.boolean)
    }

    /**
     * PRODUCT_SPEC MGR-001 / MGR-002 / MGR-004 — the default grants of an ordinary `user` account.
     *
     * Download, and nothing else. This is what `ManagementPermissions` is built to reflect, and it is the
     * reason an ordinary account sees an editor it cannot save from rather than no editor at all.
     */
    @Test
    fun `an ordinary account may download and may not manage`() {
        val permissions = json.parseToJsonElement(bodyOf("user-create-active"))
            .jsonObject.getValue("user").jsonObject.getValue("permissions").jsonObject

        assertEquals(true, permissions.getValue("download").jsonPrimitive.boolean)
        assertEquals(false, permissions.getValue("update").jsonPrimitive.boolean)
        assertEquals(false, permissions.getValue("delete").jsonPrimitive.boolean)
        assertEquals(false, permissions.getValue("upload").jsonPrimitive.boolean)
    }

    /**
     * PRODUCT_SPEC principle 4 — what the *second* enforcement is predicting, observed at last.
     *
     * Every management capture before these ran as `root`, so every response was the permitted one. These
     * three ran as an active `user` account and are the refusals: `403`, `text/plain`, body `Forbidden`.
     *
     * The finding that generalises is the content type. These handlers end with Express's `sendStatus`,
     * which writes the status *name* as a plain-text body — the same mechanism that makes cover removal,
     * library scan and item deletion answer `text/plain "OK"` on success. A client that parsed a management
     * failure as JSON would get a deserialization error where it should get a permission error, so
     * `NetworkErrorMapper` keys on the status code and never on the body.
     */
    @Test
    fun `a refused management request is plain text, not json`() {
        for (fixture in listOf("item-update-forbidden", "item-delete-forbidden", "item-scan-forbidden")) {
            val envelope = json.parseToJsonElement(rawEnvelope(fixture)).jsonObject

            assertEquals(403, envelope.getValue("status").jsonPrimitive.int, fixture)
            assertEquals("text", envelope.getValue("bodyKind").jsonPrimitive.content, fixture)
            assertTrue(envelope.getValue("contentType").jsonPrimitive.content.startsWith("text/plain"), fixture)
            assertEquals("Forbidden", envelope.getValue("bodyText").jsonPrimitive.content, fixture)
        }
    }

    /**
     * PRODUCT_SPEC MGR-004 — the scan refusal is about the account **type**, not about a grant.
     *
     * The account that was refused holds `download` and nothing else, so the update and delete refusals are
     * explained by its grants. The *scan* refusal is not: the server gates scanning on being admin or root,
     * and this account would still be refused holding every permission there is. That is why `ProfileRole`
     * does real work in `ManagementPermissions` rather than being a presentation bucket.
     */
    @Test
    fun `the refused account is an ordinary user with only the download grant`() {
        val user = json.parseToJsonElement(bodyOf("me-listener")).jsonObject
        val permissions = user.getValue("permissions").jsonObject

        assertEquals("user", user.getValue("type").jsonPrimitive.content)
        assertEquals(true, permissions.getValue("download").jsonPrimitive.boolean)
        assertEquals(false, permissions.getValue("update").jsonPrimitive.boolean)
        assertEquals(false, permissions.getValue("delete").jsonPrimitive.boolean)
        assertEquals(false, permissions.getValue("upload").jsonPrimitive.boolean)
    }

    /**
     * `GET /api/me` carries the caller's own token, which the app already holds — so this is not the leak
     * `GET /api/users` is. It is pinned anyway, because the *reason* `UserDto` models no token is that no
     * response's token is ever worth parsing, and a reader who saw this one might conclude otherwise.
     */
    @Test
    fun `even the caller's own account response carries a token that is never modelled`() {
        val user = json.parseToJsonElement(bodyOf("me-listener")).jsonObject

        assertTrue("token" in user)
    }

    // --- The book timeline (PRODUCT_SPEC PLAY-003, ADR-0016) --------------------------------------

    /**
     * **`media.tracks` is contiguous**, and the whole of PLAY-003's coordinate question rests on it.
     *
     * `docs/gaps.md` carried an open defect for four phases saying that a book with an excluded file
     * resolves positions against the wrong offsets — the player concatenates only the playable tracks
     * while the book timeline supposedly still counts the excluded one, leaving a hole. It cannot happen,
     * and the server's own source says why (`server/models/Book.js`, read at 2.36.0):
     *
     * ```js
     * get includedAudioFiles() { return this.audioFiles.filter((af) => !af.exclude) }
     *
     * getTracklist(libraryItemId) {
     *   let startOffset = 0
     *   return this.includedAudioFiles.map((af) => { ...; track.startOffset = startOffset
     *                                                startOffset += track.duration; return track })
     * }
     * ```
     *
     * Excluded files are filtered out **before** the offsets are accumulated, so `media.tracks` never
     * contains one and the offsets never contain a hole. The concatenation and the book share a
     * coordinate space by construction.
     *
     * This test is what turns that reading into something that fails when it stops being true. If a
     * future server ever emits a gap — or ships an excluded track in `tracks` — the arithmetic below
     * breaks, and the defect surfaces here rather than as a resume landing in the wrong chapter.
     */
    @Test
    fun `track offsets accumulate with no hole, so the player timeline is the book timeline`() {
        val media = json.parseToJsonElement(bodyOf("library-item")).jsonObject
            .getValue("media").jsonObject
        val tracks = media.getValue("tracks").jsonArray

        var expected = 0.0
        tracks.forEach { element ->
            val track = element.jsonObject
            assertEquals(
                false,
                track.getValue("exclude").jsonPrimitive.boolean,
                "media.tracks must never carry an excluded file — the server filters before it accumulates",
            )
            assertEquals(
                expected,
                track.getValue("startOffset").jsonPrimitive.double,
                "startOffset must equal the durations before it; a hole here is PLAY-003's defect",
            )
            expected += track.getValue("duration").jsonPrimitive.double
        }
        assertEquals(expected, media.getValue("duration").jsonPrimitive.double)
    }

    /**
     * The same, over the two-file book — the fixture that settled `startOffset` being global in the first
     * place. A single-track book cannot tell a contiguous timeline from an accumulated one.
     */
    @Test
    fun `a multi-file book's second track starts exactly where the first ends`() {
        val tracks = json.parseToJsonElement(bodyOf("multi-item-play")).jsonObject
            .getValue("audioTracks").jsonArray
            .map { it.jsonObject }

        assertEquals(2, tracks.size)
        assertEquals(0.0, tracks[0].getValue("startOffset").jsonPrimitive.double)
        assertEquals(
            tracks[0].getValue("duration").jsonPrimitive.double,
            tracks[1].getValue("startOffset").jsonPrimitive.double,
        )
        assertTrue(tracks.none { it.getValue("exclude").jsonPrimitive.boolean })
    }

    private fun bodyOf(fixture: String): String =
        json.parseToJsonElement(rawEnvelope(fixture)).jsonObject.getValue("body").toString()

    private fun rawEnvelope(fixture: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("contracts/$fixture.json")) {
            "no committed fixture named contracts/$fixture.json. Run scripts/capture-contracts.sh."
        }
        return stream.use { it.readBytes().decodeToString() }
    }
}
