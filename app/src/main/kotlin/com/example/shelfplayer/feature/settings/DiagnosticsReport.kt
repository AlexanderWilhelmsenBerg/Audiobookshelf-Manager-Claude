package com.example.shelfplayer.feature.settings

import com.example.shelfplayer.core.common.log.LoggedEvent
import com.example.shelfplayer.core.model.ServerCapability
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * PRODUCT_SPEC 14.4 / 14.5 — one block of text describing this install, safe to paste to a stranger.
 *
 * ### Why this is a pure function and not a composable
 *
 * Because the interesting property is testable and the rendering is not. `DiagnosticsReportTest` feeds it
 * state stuffed with the private values that must never appear — a hostname, a library name, a book title,
 * a headset's name, a token — and asserts none of them come out. That test is the whole point of the file;
 * a report built inline in a `@Composable` could not have one.
 *
 * ### The rule this file exists to enforce
 *
 * The event log is safe by construction: nothing reaches it except through a `LogField`, and `Redactor`
 * decides what a `LogField` is allowed to say. **`SettingsUiState` has no such protection.** It is live
 * domain state — real library names, the real server address, real device names — so a report that
 * interpolated it freely would undo every redaction the logger performs.
 *
 * So this builds from an allow-list of *counts, booleans, versions and enum names*, and never from a
 * user-supplied or server-supplied string. Where a value is genuinely useful and genuinely private, it
 * appears as a shape rather than a value: the server is described by its version and its capabilities, and
 * never by its address.
 *
 * The one apparent exception is the event log's own lines, which are included verbatim — and they are
 * already redacted, by the layer that wrote them.
 */
internal object DiagnosticsReport {

    /**
     * @param events the event log's lines, already redacted. Newest last, as the buffer holds them.
     */
    fun of(
        appVersion: String,
        state: SettingsUiState,
        metrics: com.example.shelfplayer.core.model.playback.PlaybackMetrics,
        events: List<LoggedEvent>,
        at: Instant,
    ): String = buildString {
        appendLine("BookWave diagnostics")
        appendLine("generated: ${TIMESTAMPS.format(at)}")
        appendLine("app: $appVersion")
        appendLine()

        appendLine("[server]")
        // The address is deliberately absent. PRODUCT_SPEC 14.5 treats a self-hosted host name as private,
        // and there is nothing a reader of this report can do with it that the version does not do better.
        val server = state.server
        appendLine("version: ${server?.reportedVersion ?: UNKNOWN}")
        appendLine("authMethods: ${server?.authMethods?.joinToString(",").orEmpty().ifEmpty { NONE }}")
        appendLine("handshake: ${server?.hasHandshake ?: false}")
        appendLine("socket: ${server?.socketStatus?.let { it::class.simpleName } ?: UNKNOWN}")
        appendLine("capabilities: ${capabilitiesOf(server?.confirmed.orEmpty())}")
        appendLine()

        appendLine("[libraries]")
        // Counts, never names. A library called "Erotica" is not something to paste into a bug report.
        appendLine("visible: ${state.libraries.size}")
        appendLine("hasDefault: ${state.defaultLibraryId != null}")
        appendLine()

        appendLine("[storage]")
        val storage = state.storage
        appendLine("servers: ${storage.serversStored}")
        appendLine("profiles: ${storage.profilesStored}")
        appendLine("credentials: ${storage.storedCredentials}")
        appendLine("libraries: ${storage.librariesStored} (accessible ${storage.librariesAccessible})")
        appendLine("books: ${storage.booksStored} (accessible ${storage.booksAccessible})")
        appendLine("booksSoftDeleted: ${storage.booksSoftDeleted}")
        appendLine("progress: ${storage.progressRecords} (unsynced ${storage.unsyncedProgressRecords})")
        appendLine()

        appendLine("[sessions]")
        val sync = state.sessionSync
        appendLine("recorded: ${sync.sessionsRecorded}")
        appendLine("pending: ${sync.sessionsPending}")
        appendLine("open: ${sync.sessionsOpen}")
        appendLine("synced: ${sync.sessionsSynced}")
        appendLine("progressDeclined: ${sync.progressDeclined}")
        appendLine("lastSyncedAt: ${sync.lastSyncedAt?.let(TIMESTAMPS::format) ?: NEVER}")
        appendLine("lastTrigger: ${sync.lastTrigger?.name ?: NONE}")
        // A failure *code*, which names a kind of failure. Not a summary, which can quote a server.
        appendLine("lastFailure: ${sync.lastFailureCode ?: NONE}")
        appendLine()

        appendLine("[playback]")
        appendLine("rebuffers: ${metrics.rebuffers}")
        appendLine("startupsMeasured: ${metrics.startupsMeasured}")
        appendLine("lastStartup: ${metrics.lastStartup ?: UNKNOWN}")
        appendLine("slowestStartup: ${metrics.slowestStartup ?: UNKNOWN}")
        appendLine("notificationsBlocked: ${state.notifications.isBlocked}")
        appendLine("notificationShowing: ${state.notifications.isShowing}")
        appendLine()

        appendLine("[car]")
        val car = state.car
        appendLine("declared: ${car.isDeclared}")
        appendLine("browserService: ${car.hasBrowserService}")
        appendLine("androidAutoInstalled: ${car.isAndroidAutoInstalled}")
        appendLine("sideloaded: ${car.isSideloaded}")
        appendLine()

        appendLine("[events] ${events.size}")
        // Verbatim, and safe: nothing reaches the log except through a `LogField`, and `Redactor` has
        // already decided what each one may say. This is the only place in the report where a string the
        // app did not choose appears at all.
        events.takeLast(EVENT_LINES).forEach { event ->
            appendLine("${TIMESTAMPS.format(event.at)} ${event.level.name.first()} ${event.tag} ${event.line}")
        }
    }

    /** Names only. A capability's name describes the software, never the library (PRODUCT_SPEC 14.5). */
    private fun capabilitiesOf(confirmed: Set<ServerCapability>): String =
        if (confirmed.isEmpty()) NONE else confirmed.map(ServerCapability::name).sorted().joinToString(",")

    private val TIMESTAMPS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    /**
     * How many log lines the report carries.
     *
     * The buffer holds five hundred. All of them would be a wall of text nobody pastes, and the lines that
     * explain a problem are the recent ones — somebody generating this has just watched something happen.
     */
    private const val EVENT_LINES = 120

    private const val UNKNOWN = "unknown"
    private const val NONE = "none"
    private const val NEVER = "never"
}
