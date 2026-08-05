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
    val isExplicit: Boolean,
    val isAbridged: Boolean,
    val coverPath: String?,
    val trackCount: Int,
    val sizeBytes: Long,
    val remoteUpdatedAt: Long?,
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
