package com.example.shelfplayer.playback

import android.view.KeyEvent
import kotlinx.coroutines.CancellationException
import java.util.UUID

internal enum class MediaInputEntry { ReceiverStart, SessionCallback }

/** Opt-in capability recorder. It owns no player, destination, permissions or persistent identity. */
internal class MediaInputDiagnostics(
    private val source: MediaInputSource,
    private val emit: (String) -> Unit,
    private val publish: (String, List<String>) -> Unit,
    private val captureId: () -> String = { "m" + UUID.randomUUID().toString().replace("-", "") },
) {
    private class Capture(val id: String, val timeline: MediaInputTimeline = MediaInputTimeline()) {
        var unsubscribe: (() -> Unit)? = null
    }

    private var active: Capture? = null

    @Synchronized
    fun start() {
        if (active != null) return
        val capture = Capture(captureId())
        active = capture
        record(capture, "input-capture-start aliases=CaptureLocal association=Unproven")
        try {
            observe(capture)
        } catch (cancelled: CancellationException) {
            stop()
            throw cancelled
        }
    }

    private fun observe(capture: Capture) {
        val observer = read { source.subscribe { changed -> sample(capture, changed.name) } }.getOrNull()
        if (active !== capture) {
            observer?.invoke()
            return
        }
        capture.unsubscribe = observer
        if (observer == null) record(capture, "input-callback-unavailable")
        sample(capture, "Start")
    }

    @Synchronized
    fun stop() {
        val capture = active ?: return
        active = null
        read { capture.unsubscribe?.invoke() }
        emit("input-capture-stop inputCapture=${capture.id}")
        publish(
            "Stopped; aliases belong only to the preceding capture",
            capture.timeline.current.map { "inputCapture=${capture.id} $it" },
        )
    }

    @Synchronized
    fun mediaKey(event: KeyEvent?, controller: MediaInputController, entry: MediaInputEntry) {
        val capture = active ?: return
        if (event == null) {
            record(capture, "input-key entry=$entry keyPresent=false ${controller.fields()}")
            return
        }
        val query = read { source.device(event) }
        val device = query.getOrNull()
        val resolution = when {
            query.isFailure -> "Unavailable"
            device == null -> "Missing"
            else -> "Resolved"
        }
        val fields = "input-key entry=$entry keyPresent=true keyCode=${event.keyCode} keyAction=${event.action} " +
            "keyInputId=${event.deviceId} keyInputSource=${event.source} keyFlags=${event.flags} " +
            "repeat=${event.repeatCount} longPress=${event.isLongPress} cancelled=${event.isCanceled} " +
            "heldMillis=${(event.eventTime - event.downTime).coerceAtLeast(0)} deviceQuery=$resolution"
        record(
            capture,
            "$fields ${controller.fields()} ${device?.let(capture.timeline::eventDevice) ?: "association=Unproven"}",
        )
    }

    @Synchronized
    fun transport(play: Boolean, controller: MediaInputController) {
        val capture = active ?: return
        record(
            capture,
            "input-transport requested=${if (play) "Play" else "Pause"} ${controller.fields()} association=Unproven",
        )
    }

    @Synchronized
    private fun sample(capture: Capture, reason: String) {
        if (active !== capture) return
        val devices = read(source::devices).getOrNull()
        if (active !== capture) return
        if (devices == null) {
            record(capture, "input-inventory-unavailable")
            publish("Input inventory unavailable", capture.timeline.current.map { "inputCapture=${capture.id} $it" })
            return
        }
        capture.timeline.observe(devices).forEach { record(capture, "$it reason=$reason") }
        val status = if (capture.unsubscribe ==
            null
        ) {
            "Input callbacks unavailable; inventory sampled"
        } else {
            "Watching input callbacks"
        }
        publish(status, capture.timeline.current.map { "inputCapture=${capture.id} $it" })
    }

    private fun record(capture: Capture, fields: String) {
        if (active === capture) emit("$fields inputCapture=${capture.id}")
    }

    private inline fun <T> read(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (expected: Exception) {
        Result.failure(expected)
    }
}
