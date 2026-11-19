package com.example.shelfplayer.playback

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant

/** One private atomic file per drive. A busy drive never rewrites the previous month of exports. */
internal class AndroidAutoCaptureStore(private val folder: File) {
    fun write(attempt: JSONObject): Boolean {
        folder.mkdirs()
        val file = AtomicFile(File(folder, "${attempt.getLong("attempt")}.json"))
        return try {
            val stream = file.startWrite()
            try {
                stream.write(attempt.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
                true
            } catch (_: IOException) {
                file.failWrite(stream)
                false
            }
        } catch (_: IOException) {
            false
        }
    }

    fun read(cutoff: Instant): List<JSONObject> = files().mapNotNull { file ->
        runCatching {
            val row = JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8))
            row.takeIf { !Instant.parse(row.getString("startedAtUtc")).isBefore(cutoff) }
        }.getOrNull()
    }

    fun prune(cutoff: Instant) {
        files().let { files ->
            val old = files.dropLast(AndroidAutoDiagnosticsCapture.MAX_ATTEMPTS)
            files.filter { it in old || Instant.ofEpochMilli(it.lastModified()).isBefore(cutoff) }
                .forEach { AtomicFile(it).delete() }
        }
    }

    fun clear() {
        folder.listFiles().orEmpty().forEach { it.delete() }
    }

    private fun files(): List<File> = folder.listFiles().orEmpty()
        .filter { it.extension == "json" && it.nameWithoutExtension.toLongOrNull() != null }
        .sortedBy { it.nameWithoutExtension.toLong() }
}
