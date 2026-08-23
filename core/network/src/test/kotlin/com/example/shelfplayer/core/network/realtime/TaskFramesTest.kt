package com.example.shelfplayer.core.network.realtime

import com.example.shelfplayer.core.model.realtime.ServerTask
import com.example.shelfplayer.core.network.api.ContractFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC MGR-007 / 14.5 — the task frame, and the four fields that must never come out of it.
 *
 * ### Why the payloads here are long
 *
 * Because they are the server's, not mine. `TaskManager` emits `task.toJSON()` whole, and the whole thing
 * includes `title`, `titleSubs`, `description` and `descriptionSubs` — and Audiobookshelf's own description
 * for this task is `Embedding metadata in audiobook "<title>"`. **The book's title arrives in three separate
 * fields.** Trimming the fixtures to the fields the parser reads would delete the entire risk this file
 * exists to guard.
 *
 * ### The hand-written frames are no longer the only evidence
 *
 * This file used to say its payloads were "source-derived rather than captured", because starting an embed
 * needs an administrator on a server this project can reach. `socket-embed-task.json` is now that capture —
 * a real `task_started`, `track_started`, `track_finished` and `task_finished` from Audiobookshelf 2.36.0 —
 * and [parses the captured task_finished frame] runs the parser over it. The hand-written frames stay: they
 * plant the title in every field the server puts it in and can vary one at a time, which a single capture
 * cannot. Evidence and coverage are different jobs.
 */
class TaskFramesTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** The private value planted in every field the server puts it in. */
    private val bookTitle = "The Salt Harbour"

    // --------------------------------------- the captured frame, PRODUCT_SPEC 22.5

    /**
     * **The six fields the parser names, against a frame the server actually sent.**
     *
     * `TaskFrames.parse` was written from Audiobookshelf's source. The whole embed progress surface —
     * `EmbedTaskWatcher`, `BookViewModel`'s pending state, the confirmation the user reads — hangs off these
     * six names, and a single one being wrong would mean the UI silently never completes. A hand-written
     * fixture cannot catch that, because it agrees with the parser by construction.
     *
     * Every one of them is right, which is worth recording as much as a defect would have been: it is the
     * difference between "we think this works" and "we watched it work".
     */
    @Test
    fun `parses the captured task_finished frame`() {
        val task = TaskFrames.parse(capturedTaskBody("task_finished"))

        assertNotNull(task)
        assertEquals("embed-metadata", task.action)
        assertTrue(task.isFinished, "the captured frame is the finished one")
        assertFalse(task.isFailed)
        assertFalse(task.hasError, "the captured embed succeeded, so it carries no reason")
        assertNotNull(task.libraryItemId, "read from data.libraryItemId, which the capture confirms is there")
    }

    /** And the start of the same task reads as unfinished, so the two are told apart on real frames. */
    @Test
    fun `parses the captured task_started frame as unfinished`() {
        val task = TaskFrames.parse(capturedTaskBody("task_started"))

        assertNotNull(task)
        assertFalse(task.isFinished)
    }

    /**
     * **The private half, confirmed present and confirmed unread.**
     *
     * `TaskFrames`' own comment predicted what a `@Serializable` DTO would have to skip: *"the next person
     * to need something adds `description`, which is `Embedding metadata in audiobook "<the book's
     * title>"`"*. The capture proves that prediction verbatim, and adds three more — `descriptionSubs`
     * carries the title again, and `data` carries `libraryItemDir`, `itemCachePath` and `audioFiles[].path`,
     * which are filesystem paths inside somebody's library (PRODUCT_SPEC 14.5).
     *
     * Asserted against the parsed task's own `toString`, because that is what a log line or a crash report
     * would render. The frame is allowed to contain all of it; [ServerTask] is not.
     */
    @Test
    fun `nothing private in the captured frame survives parsing`() {
        val body = capturedTaskBody("task_finished")
        // Asserted non-null first, and not for tidiness: a `null` task renders as the string "null", and
        // every assertion below would pass without reading anything.
        val task = assertNotNull(TaskFrames.parse(body))
        val rendered = task.toString()

        // The frame really does carry these, or this test would be proving nothing.
        val raw = body.toString()
        assertTrue(raw.contains("libraryItemDir"), "the capture must still show the private half exists")
        assertTrue(raw.contains("description"), "including the title-bearing description")

        for (private in listOf("Salt Harbour", "libraryItemDir", "audiobooks", "itemCachePath", "cachePath")) {
            assertFalse(rendered.contains(private), "$private reached the parsed task")
        }
    }

    /**
     * The captured poll also carries `track_started` and `track_finished`, which are not tasks.
     *
     * Newly observed, and the parser must decline them rather than invent a task with no action — 22.4's
     * rule for a shape this build does not model.
     */
    @Test
    fun `the captured track frames are not read as tasks`() {
        for (event in listOf("track_started", "track_finished")) {
            assertNull(TaskFrames.parse(capturedTaskBody(event)), "$event is not a task")
        }
    }

    /** The `42["event", { … }]` payload for [event], out of the captured poll. */
    private fun capturedTaskBody(event: String): JsonObject = ContractFixtures.frames("socket-embed-task")
        .mapNotNull { frame -> frame["payload"]?.jsonArray?.takeIf { it.size >= 2 } }
        .first { payload -> payload[0].jsonPrimitive.content == event }[1]
        .jsonObject

    private fun frame(
        action: String = "embed-metadata",
        itemId: String = "li_9x2k",
        isFinished: Boolean = true,
        isFailed: Boolean = false,
        error: String? = null,
    ): JsonObject = json.parseToJsonElement(
        """
        {
          "id": "task_7",
          "action": "$action",
          "data": {
            "libraryItemId": "$itemId",
            "libraryItemDir": "/srv/audiobooks/Fiction/$bookTitle",
            "userId": "usr_1",
            "coverPath": "/metadata/items/li_9x2k/cover.jpg"
          },
          "title": "Embedding Metadata",
          "titleKey": "MessageTaskEmbeddingMetadata",
          "titleSubs": null,
          "description": "Embedding metadata in audiobook \"$bookTitle\"",
          "descriptionKey": "MessageTaskEmbeddingMetadataDescription",
          "descriptionSubs": ["$bookTitle"],
          "error": ${if (error == null) "null" else "\"$error\""},
          "errorKey": null,
          "errorSubs": null,
          "showSuccess": true,
          "isFailed": $isFailed,
          "isFinished": $isFinished,
          "startedAt": 1755600000000,
          "finishedAt": 1755600180000
        }
        """.trimIndent(),
    ) as JsonObject

    @Test
    fun `a finished embed is recognised, with the item it was about`() {
        val task = TaskFrames.parse(frame())

        assertEquals("task_7", task?.id)
        assertEquals(ServerTask.EMBED_METADATA, task?.action)
        assertEquals("li_9x2k", task?.libraryItemId)
        assertEquals(true, task?.isFinished)
        assertEquals(false, task?.isFailed)
        assertEquals(true, task?.isEmbedMetadata)
    }

    /**
     * **The reason this file exists.** The title is in `description`, in `descriptionSubs` and inside
     * `data.libraryItemDir`, and none of the three may survive the parse.
     */
    @Test
    fun `no private value survives the parse`() {
        // `assertNotNull` and not `?.`: a parse that returned null would make every assertion below pass
        // vacuously, which is the one way this test could look green while the property was broken.
        val task = assertNotNull(
            TaskFrames.parse(frame(error = "ffmpeg exited 1 on /srv/audiobooks/Fiction/$bookTitle/01.m4b")),
        )

        assertFalse(task.toString().contains(bookTitle), "the task leaked the book's title")
        assertFalse(task.toString().contains("/srv/"), "the task leaked a server path")
        assertFalse(task.toString().contains("ffmpeg"), "the task leaked the server's error text")
    }

    /** A failure says *that* it failed and that a reason exists. The reason itself stays on the server. */
    @Test
    fun `a failure carries whether there was a reason, never the reason`() {
        val task = assertNotNull(TaskFrames.parse(frame(isFailed = true, error = "Failed to write metadata")))

        assertEquals(true, task.isFailed)
        assertEquals(true, task.hasError)
        assertFalse(task.toString().contains("Failed to write"))
    }

    /** A failure the server did not explain is a different thing to tell the user. */
    @Test
    fun `a failure with no reason reports no reason`() {
        val task = TaskFrames.parse(frame(isFailed = true, error = null))

        assertEquals(true, task?.isFailed)
        assertEquals(false, task?.hasError)
    }

    /** A server runs tasks nobody on this device asked for, and they arrive on the same stream. */
    @Test
    fun `another kind of task is parsed but not claimed as an embed`() {
        val task = TaskFrames.parse(frame(action = "encode-m4b"))

        assertEquals("encode-m4b", task?.action)
        assertFalse(task?.isEmbedMetadata == true)
    }

    /**
     * PRODUCT_SPEC 22.4 — a frame missing the fields that identify it is ignored, not half-read.
     *
     * A task with no action could not be matched against anything, and a task with no id could not be told
     * apart from another one. Guessing either would be inventing server behaviour.
     */
    @Test
    fun `a frame with no id or no action is ignored`() {
        assertNull(TaskFrames.parse(json.parseToJsonElement("""{"action":"embed-metadata"}""") as JsonObject))
        assertNull(TaskFrames.parse(json.parseToJsonElement("""{"id":"task_7"}""") as JsonObject))
        assertNull(TaskFrames.parse(json.parseToJsonElement("""{"id":"","action":""}""") as JsonObject))
    }

    /** A library scan has no item. Null rather than an empty string, so a caller cannot match on it. */
    @Test
    fun `a task about no particular item has no item id`() {
        val scan = json.parseToJsonElement(
            """{"id":"task_8","action":"library-scan","data":{},"isFinished":true,"isFailed":false}""",
        ) as JsonObject

        assertNull(TaskFrames.parse(scan)?.libraryItemId)
    }

    /** `task_started` is the same shape with both flags false, which is what makes it a *progress* signal. */
    @Test
    fun `a started task is neither finished nor failed`() {
        val task = TaskFrames.parse(frame(isFinished = false, isFailed = false))

        assertTrue(task?.isFinished == false && task.isFailed == false)
    }
}
