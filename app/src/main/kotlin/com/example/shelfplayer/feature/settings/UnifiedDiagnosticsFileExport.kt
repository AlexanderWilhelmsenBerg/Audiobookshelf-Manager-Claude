package com.example.shelfplayer.feature.settings

import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The SAF file picker provides a user-approved destination. Do not truncate or
 * rebuild the privacy-scrubbed report: this writes precisely the copyable JSON.
 */
internal object UnifiedDiagnosticsFileExport {
    private val dateFormat = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss")
        .withZone(ZoneOffset.UTC)

    fun filename(at: Instant): String = "bookwave-diagnostics-${dateFormat.format(at)}.json"

    fun write(output: OutputStream, report: String) {
        output.use { it.write(report.toByteArray(Charsets.UTF_8)) }
    }
}
