package com.example.shelfplayer.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * PRODUCT_SPEC 13 — the Phase 0 subset of the conceptual schema.
 *
 * Every remote entity carries `serverId` + `remoteId` alongside its derived key column
 * (see [EntityKey]), and every entity carries the freshness columns PRODUCT_SPEC 13.2 requires:
 * `remoteUpdatedAt`, `lastFetchedAt` and `isDeleted`. They are populated from the first commit
 * rather than added later, because retrofitting a soft-delete column onto a shipped database is
 * exactly the kind of change that tempts a destructive migration.
 *
 * Timestamps are epoch milliseconds and durations are milliseconds. Both are stored as `INTEGER`
 * with no type converter, so the exported schema is readable and a migration never has to guess how
 * a converter used to behave.
 */
/**
 * `[]` rather than `''`, even though `StringListConverters` reads both as an empty list.
 *
 * A schema default is read by a human comparing `1.json` with `2.json`, and `[]` says "an empty JSON
 * array" where `''` says "we are not sure what this column holds".
 */
private const val EMPTY_JSON_ARRAY = "[]"

/**
 * PRODUCT_SPEC SYNC-001 — the capability handshake is persisted here rather than in its own table.
 *
 * PRODUCT_SPEC 13 names a conceptual `ServerCapabilityEntity` and allows normalization to vary. A row
 * per capability would buy per-capability queries, and nothing needs them: a handshake is written as
 * one set and read as one set, always for a single server. Two JSON columns and a timestamp carry the
 * same information with one fewer table, one fewer DAO and no join.
 *
 * The stored set is only the *supported* capabilities. A capability the handshake did not confirm is
 * simply absent, which is what makes "unknown means unsupported" a property of the storage shape
 * rather than a rule the reading code has to remember (PRODUCT_SPEC SYNC-001).
 */
@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey val serverId: String,
    val displayName: String,
    val baseUrl: String,
    val detectedVersion: String?,
    val isFixture: Boolean,
    val lastFetchedAt: Long,
    /** The authentication modes `GET /status` reported, e.g. `["local"]`. JSON array. */
    @ColumnInfo(defaultValue = EMPTY_JSON_ARRAY) val authMethodsJson: String,
    /** Names of the confirmed [com.example.shelfplayer.core.model.ServerCapability] values. JSON array. */
    @ColumnInfo(defaultValue = EMPTY_JSON_ARRAY) val capabilitiesJson: String,
    /** `null` until a handshake has run, which the UI must distinguish from "nothing is supported". */
    val capabilitiesDetectedAt: Long?,
)

@Entity(
    tableName = "profiles",
    foreignKeys = [
        ForeignKey(
            entity = ServerEntity::class,
            parentColumns = ["serverId"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("serverId")],
)
data class ProfileEntity(
    @PrimaryKey val profileId: String,
    val serverId: String,
    /**
     * The server's own id for this account, when it sent one (PRODUCT_SPEC 13.1).
     *
     * Stored even though [profileId] is derived from it, because PRODUCT_SPEC 5.2 refreshes
     * permissions from `POST /api/authorize` after a `403`: comparing the account that responds with
     * the account this profile was created for is what catches a stored token that now belongs to
     * someone else. Nullable, because a server that did not send one is not given a made-up value.
     */
    val remoteUserId: String?,
    val username: String,
    val displayName: String,
    val role: String,
    val requiresReauthentication: Boolean,
    val lastUsedAt: Long?,
    val isFixture: Boolean,
    /**
     * PRODUCT_SPEC 5.2 — the server's library grant, persisted rather than kept in the session.
     *
     * A sync has to apply the grant while holding nothing but a profile id: the session object that
     * carried it lives only for the duration of a sign-in, and the sync that must honour it runs later,
     * possibly after a process restart. Storing it is what makes the Phase 1 exit criterion —
     * "unauthorized libraries never appear" — enforceable at the moment rows would be written.
     *
     * The two fields are not redundant. `accessAllLibraries` with an **empty** `librariesAccessible`
     * means *all libraries* on Audiobookshelf 2.36.0, so an empty list cannot be read as "none".
     */
    @ColumnInfo(defaultValue = EMPTY_JSON_ARRAY) val accessibleLibrariesJson: String,
    @ColumnInfo(defaultValue = "0") val hasAllLibraryAccess: Boolean,
    /**
     * PRODUCT_SPEC 5.2 — whether the server serves this account an unfiltered *item* list.
     *
     * Separate from the library grant because Audiobookshelf restricts twice, and only an account with
     * both may be trusted to say a book is gone by not returning it. Defaults to `0`: an account whose
     * item access is unknown adds rows but never deletes them.
     */
    @ColumnInfo(defaultValue = "0") val hasAllTagAccess: Boolean,
)

@Entity(
    tableName = "libraries",
    foreignKeys = [
        ForeignKey(
            entity = ServerEntity::class,
            parentColumns = ["serverId"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("serverId")],
)
data class LibraryEntity(
    @PrimaryKey val libraryKey: String,
    val serverId: String,
    val remoteId: String,
    val name: String,
    val kind: String,
    val displayOrder: Int,
    val remoteUpdatedAt: Long?,
    val lastFetchedAt: Long,
    val isDeleted: Boolean,
)

@Entity(tableName = "authors", indices = [Index("serverId")])
data class AuthorEntity(
    @PrimaryKey val authorKey: String,
    val serverId: String,
    val remoteId: String,
    val name: String,
)

@Entity(tableName = "series", indices = [Index("serverId")])
data class SeriesEntity(
    @PrimaryKey val seriesKey: String,
    val serverId: String,
    val remoteId: String,
    val name: String,
)

@Entity(
    tableName = "books",
    foreignKeys = [
        ForeignKey(
            entity = LibraryEntity::class,
            parentColumns = ["libraryKey"],
            childColumns = ["libraryKey"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("libraryKey"), Index("serverId"), Index("title")],
)
data class BookEntity(
    @PrimaryKey val bookKey: String,
    val serverId: String,
    val remoteId: String,
    val libraryKey: String,
    val title: String,
    val subtitle: String?,
    /** JSON array; see `StringListConverters`. */
    val narratorsJson: String,
    val genresJson: String,
    val tagsJson: String,
    val durationMillis: Long,
    val description: String?,
    val publishedYear: Int?,
    val publisher: String?,
    val language: String?,
    /**
     * PRODUCT_SPEC LIB-002 — searchable identifiers.
     *
     * Nullable and unindexed. Both are absent on most self-hosted items, and an index over a column
     * that is null for 490 of 491 rows costs writes to buy nothing; the search is a `LIKE` over cached
     * rows in memory, not a query.
     */
    val isbn: String?,
    val asin: String?,
    val isExplicit: Boolean,
    val isAbridged: Boolean,
    val coverPath: String?,
    val trackCount: Int,
    val sizeBytes: Long,
    val remoteUpdatedAt: Long?,
    /** PRODUCT_SPEC LIB-002 — the server's own "added" timestamp, not the day this cache fetched it. */
    val addedAt: Long?,
    val lastFetchedAt: Long,
    val isDeleted: Boolean,
    /** PRODUCT_SPEC DL-001 — `NotDownloaded`, `Partial` or `Complete`. */
    val localAvailability: String,
)

@Entity(
    tableName = "book_authors",
    primaryKeys = ["bookKey", "authorKey"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["bookKey"],
            childColumns = ["bookKey"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AuthorEntity::class,
            parentColumns = ["authorKey"],
            childColumns = ["authorKey"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("authorKey")],
)
data class BookAuthorCrossRef(val bookKey: String, val authorKey: String, val position: Int)

@Entity(
    tableName = "book_series",
    primaryKeys = ["bookKey", "seriesKey"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["bookKey"],
            childColumns = ["bookKey"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SeriesEntity::class,
            parentColumns = ["seriesKey"],
            childColumns = ["seriesKey"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("seriesKey")],
)
data class BookSeriesCrossRef(
    val bookKey: String,
    val seriesKey: String,
    /**
     * PRODUCT_SPEC LIB-003 — the sequence exactly as the server sent it.
     *
     * The parsed numeric value is derived on read rather than stored, so a fix to the parser applies
     * to already-synced libraries without a migration.
     */
    val sequenceRaw: String?,
    val isPrimary: Boolean,
)

@Entity(
    tableName = "audio_tracks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["bookKey"],
            childColumns = ["bookKey"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookKey")],
)
data class AudioTrackEntity(
    @PrimaryKey val trackKey: String,
    val bookKey: String,
    val serverId: String,
    val trackIndex: Int,
    val remoteFileId: String,
    /** PRODUCT_SPEC 11.3 — offset of this track on the global book timeline. */
    val startOffsetMillis: Long,
    val durationMillis: Long,
    val mimeType: String?,
    val sizeBytes: Long,
    val isExcluded: Boolean,
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["bookKey"],
            childColumns = ["bookKey"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookKey")],
)
data class ChapterEntity(
    @PrimaryKey val chapterKey: String,
    val bookKey: String,
    val serverId: String,
    val chapterIndex: Int,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
)

/**
 * PRODUCT_SPEC 5.2 — which items one profile may actually see.
 *
 * ### Why a table and not a predicate
 *
 * `LibraryAccess` answers "may this profile read this *library*", and that is all the server tells us
 * up front. Audiobookshelf restricts a second time, by tag, *inside* a library — and it reports that
 * restriction nowhere except by silently shortening the item list it serves. So there is no predicate
 * to evaluate: the only evidence of item-level visibility is which ids came back, and the only place to
 * keep it is a row per profile per item.
 *
 * A device run is what forced this. Account A cached 490 books from a shared library; account B, which
 * the server restricts by tag, has `hasAllLibraryAccess = true` and so passed every library-level check
 * — and saw all 490 of A's books, online and offline alike. That is product priority 4, crossing a
 * permission boundary, and it is the one Phase 1 exit criterion that was failing.
 *
 * ### Two deliberate choices
 *
 * **Absence means hidden.** A profile with no rows here sees no books. That is the opposite of the
 * usual "fail open" instinct and it is chosen on purpose: an empty shelf until the first sync is a
 * visible, self-correcting annoyance, whereas showing another account's library is a silent leak.
 *
 * **No foreign key on [bookKey].** The rows are written from the server's catalogue, which lists items
 * whose expanded fetch may have failed and which therefore have no `books` row yet. Visibility is a
 * statement about what the server showed this account, not about what we managed to cache, and a
 * foreign key would make the weaker fact govern the stronger one. The profile key *is* constrained, so
 * removing a profile takes its visibility with it.
 */
@Entity(
    tableName = "profile_visible_books",
    primaryKeys = ["profileId", "bookKey"],
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["profileId"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookKey"), Index("profileId", "libraryKey")],
)
data class ProfileVisibleBookEntity(
    val profileId: String,
    val bookKey: String,
    /** Scopes the replace-on-sync: one library's visibility is rewritten without touching another's. */
    val libraryKey: String,
)

/**
 * PRODUCT_SPEC PLAY-004 — one profile's position in one book.
 *
 * [hasUnsyncedChanges] is what stops PRODUCT_SPEC DL-006 from deleting a book whose progress has not
 * reached the server yet.
 */
@Entity(
    tableName = "media_progress",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["bookKey"],
            childColumns = ["bookKey"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["profileId"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookKey"), Index("profileId")],
)
data class MediaProgressEntity(
    @PrimaryKey val progressKey: String,
    val profileId: String,
    val bookKey: String,
    val serverId: String,
    val positionMillis: Long,
    val durationMillis: Long,
    val isFinished: Boolean,
    val updatedAt: Long,
    val hasUnsyncedChanges: Boolean,
)

/** PRODUCT_SPEC LIB-001 — sync status per profile, so the UI can be informative without blocking. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val profileId: String,
    val serverId: String,
    val status: String,
    val lastSuccessfulSyncAt: Long?,
    val lastAttemptedAt: Long?,
    val lastErrorCode: String?,
    val lastErrorSummary: String?,
)

/**
 * PRODUCT_SPEC PLAY-008 / SET-002 — one recorded sleep-timer session.
 *
 * ### Why this is a table and not a preference
 *
 * "Did the timer fire, or did I cancel it?" is a question about a *sequence of events*, and the only
 * honest answer to it is a log. A single "last timer" field would answer it for one night and lose the
 * pattern, which is the part worth having.
 *
 * ### No foreign key to books
 *
 * Deliberate, unlike every other table here. A timer's history must outlive the book it was set on: a
 * book that leaves the server, or a library the profile loses access to, would take its rows with it
 * under a cascade — deleting the user's own record of what their phone did. [bookKey] is stored as a
 * plain string and resolved on read, with an unresolvable one rendering as "a book that is no longer in
 * the library".
 *
 * The profile key *does* cascade: removing an account removes what it did (PRODUCT_SPEC AUTH-002).
 *
 * [modeLength] is `0` for an end-of-chapter timer, which has no length of its own.
 */
@Entity(
    tableName = "sleep_timer_sessions",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["profileId"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profileId"), Index("startedAt")],
)
data class SleepTimerSessionEntity(
    @PrimaryKey val sessionId: String,
    val profileId: String,
    val bookKey: String,
    /** `Fixed` or `EndOfChapter`; an unrecognized value reads back as `Fixed` (PRODUCT_SPEC SYNC-001). */
    val mode: String,
    val modeLength: Long,
    val startedAt: Long,
    /** `null` while the timer is still running. */
    val endedAt: Long?,
    val outcome: String?,
    val restarts: Int,
)

/**
 * PRODUCT_SPEC PLAY-004 / PLAY-005 — one listening session, and the outbox row that gets it to the server.
 *
 * ### One table, not two
 *
 * An "active session" table plus an "outbox" table would need a hand-off between them, and the hand-off is
 * exactly where a session gets lost: the process dies between the delete and the insert and nobody ever
 * knows the listener was there. One row per session, transitioning through [state], has no hand-off.
 *
 * ### [sessionId] is ours, always
 *
 * A UUIDv4 this device generated (PLAY-005). It is the id the offline route uploads under, and reusing it on
 * a retry is what makes the retry idempotent rather than duplicating the session. [remoteSessionId] is the
 * *separate* id the server issued when the session was opened online, and it is `null` for a session that
 * has never been to the server — which is the whole reason the two are not one column.
 *
 * ### No foreign key to books
 *
 * As with `sleep_timer_sessions`, and for the same reason: an outbox row must survive the book leaving the
 * library, or a server-side deletion would silently discard listening the user has not uploaded yet
 * (PLAY-005 — "a sync conflict never deletes local playback history silently"). The profile key does
 * cascade: removing an account removes what it did.
 *
 * ### [title] and [author] are stored, and that is deliberate
 *
 * The offline route sends `displayTitle`/`displayAuthor`, which is what makes the session legible in the
 * server's own listening history. Resolving them at upload time would fail for exactly the book that left
 * the library, so they are copied when the session opens.
 *
 * @property state `Open`, `Pending` or `Synced` — see `SessionOutboxState`.
 * @property timeListenedMillis audio actually played, accumulated across the session. Not a position delta:
 *   a seek moves the position without anybody having listened to the difference.
 * @property updatedAt the honest moment [positionMillis] was recorded. The server resolves conflicts on
 *   this, so it is never rounded, defaulted or stamped at upload time.
 * @property wasProgressApplied what the server said about the *position* — `null` until it has answered.
 *   `false` is not a failure: it means the server held something newer (PLAY-004's conflict rule).
 * @property attempts upload attempts, for diagnostics. Not a retry limit: an outbox that gives up loses
 *   progress, which product priority 2 forbids.
 */
@Entity(
    tableName = "playback_sessions",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["profileId"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profileId"), Index("state"), Index("updatedAt")],
)
data class PlaybackSessionEntity(
    @PrimaryKey val sessionId: String,
    val profileId: String,
    val serverId: String,
    val bookKey: String,
    /** The server's own item id, which is what the upload carries. `bookKey` is scoped and local. */
    val remoteBookId: String,
    /** `null` for a session that was never opened against the server. */
    val remoteSessionId: String?,
    val title: String,
    val author: String?,
    val state: String,
    val positionMillis: Long,
    val durationMillis: Long,
    val timeListenedMillis: Long,
    val startedAt: Long,
    val updatedAt: Long,
    /** `null` until the server has accepted the row. */
    val syncedAt: Long?,
    val wasProgressApplied: Boolean?,
    val attempts: Int,
    /** The last failure's error code, or `null`. A code, never a message — a message can carry a host. */
    val lastErrorCode: String?,
)
