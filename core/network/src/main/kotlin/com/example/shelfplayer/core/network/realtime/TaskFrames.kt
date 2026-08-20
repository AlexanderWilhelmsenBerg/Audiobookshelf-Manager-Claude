package com.example.shelfplayer.core.network.realtime

import com.example.shelfplayer.core.model.realtime.ServerTask
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * PRODUCT_SPEC MGR-007 / 14.5 — a `task_started` or `task_finished` frame, with its private half never read.
 *
 * ### Why this is a separate object and not a method on the connection
 *
 * Because the interesting property is testable and the socket is not. `TaskFramesTest` feeds it a real frame
 * carrying a book's title in three different fields and asserts that none of them comes out. A private
 * method inside a `callbackFlow` could not have that test, and this is precisely the kind of mapping that
 * acquires a field later "for diagnostics".
 *
 * ### Read field by field, deliberately
 *
 * A `@Serializable` DTO would be shorter and would have to *name* the fields it skips — at which point the
 * next person to need something adds `description`, which is
 * `Embedding metadata in audiobook "<the book's title>"`. Reading only the five values [ServerTask] holds
 * means the private ones are never deserialized at all, so there is nothing in memory to leak.
 */
internal object TaskFrames {

    /**
     * The task, or `null` for a frame that does not describe one.
     *
     * `null` rather than a partially filled task: a frame with no `id` or no `action` is either a shape this
     * build does not understand or a server that changed, and PRODUCT_SPEC 22.4 says the answer to both is
     * to ignore it rather than to invent the missing half.
     */
    fun parse(body: JsonObject): ServerTask? {
        val id = body[ID]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
        val action = body[ACTION]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
        return ServerTask(
            id = id,
            action = action,
            libraryItemId = body[DATA]?.jsonObject
                ?.get(ITEM_ID)?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank),
            isFinished = body[FINISHED]?.jsonPrimitive?.booleanOrNull ?: false,
            isFailed = body[FAILED]?.jsonPrimitive?.booleanOrNull ?: false,
            // Whether the server attached a reason, never the reason. It can quote a path inside somebody's
            // library, and a path is private self-hosted data (PRODUCT_SPEC 14.5).
            hasError = body[ERROR]?.jsonPrimitive?.contentOrNull?.isNotBlank() ?: false,
        )
    }

    private const val ID = "id"
    private const val ACTION = "action"
    private const val DATA = "data"
    private const val ITEM_ID = "libraryItemId"
    private const val FINISHED = "isFinished"
    private const val FAILED = "isFailed"
    private const val ERROR = "error"
}
