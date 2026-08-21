package com.example.shelfplayer.core.network.realtime

import com.example.shelfplayer.core.model.realtime.ServerTask
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * These are source-derived rather than captured, and `docs/gaps.md` says so: starting an embed needs an
 * administrator on a server this project can reach, and the public demo account is refused.
 */
class TaskFramesTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** The private value planted in every field the server puts it in. */
    private val bookTitle = "The Salt Harbour"

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
