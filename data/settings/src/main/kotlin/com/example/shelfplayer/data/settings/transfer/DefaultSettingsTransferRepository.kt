package com.example.shelfplayer.data.settings.transfer

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.database.dao.ProfileDao
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.database.entity.ServerEntity
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.resultOf
import com.example.shelfplayer.core.model.settings.SettingsExport
import com.example.shelfplayer.core.model.settings.SettingsImport
import com.example.shelfplayer.domain.repository.SettingsTransferRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * PRODUCT_SPEC SET-001 — the settings export and import.
 *
 * Two collaborators and no third: the settings store, and the profile table that turns a locally generated
 * profile id into something a *second* install could recognise. Everything between them is
 * [SettingsTransfer], which is pure and where the decisions worth testing live.
 *
 * Fixture rows are excluded from both directions. The bundled demo server is not somebody's server, and an
 * export that offered it as an address to sign in to would be offering a library that only exists on this
 * device.
 */
class DefaultSettingsTransferRepository @Inject constructor(
    private val settings: AppSettingsDataSource,
    private val profiles: ProfileDao,
    private val clock: AppClock,
    private val logger: Logger,
) : SettingsTransferRepository {

    override suspend fun export(): AppResult<SettingsExport> = resultOf(onError = ::asAppError) {
        val stored = settings.current()
        val servers = profiles.observeServers().first().filterNot(ServerEntity::isFixture)
        val byServerId = servers.associateBy(ServerEntity::serverId)
        val profileKeys = profiles.observeProfiles().first()
            .filterNot(ProfileEntity::isFixture)
            .mapNotNull { profile -> byServerId[profile.serverId]?.let { profile.profileId to it.keyFor(profile) } }
            .toMap()
        val now = clock.now()
        val document = SettingsTransfer.document(
            settings = stored,
            servers = servers.map { server ->
                ServerDocument(
                    baseUrl = server.baseUrl,
                    displayName = server.displayName,
                    detectedVersion = server.detectedVersion,
                )
            },
            exportedAt = DateTimeFormatter.ISO_INSTANT.format(now),
            profileKeys = profileKeys,
        )
        // PRODUCT_SPEC 14.5 — counts, and never an address or a username. The file belongs to the user; a
        // log line is something they may hand to somebody else.
        logger.info(
            LogCategory.Settings,
            "Exported the settings",
            LogField.Public("servers", servers.size.toString()),
            LogField.Public("profiles", profileKeys.size.toString()),
        )
        SettingsExport(
            document = SettingsTransfer.JSON.encodeToString(document),
            suggestedFileName = "bookwave-settings-${FILE_DATE.format(now)}.json",
        )
    }

    override suspend fun import(document: String): AppResult<SettingsImport> = resultOf(onError = ::asAppError) {
        val parsed = parse(document)
        val profileIds = currentProfileIds()

        settings.importSettings { current -> SettingsTransfer.merged(current, parsed, profileIds) }

        val outcome = SettingsTransfer.profileOutcome(parsed, profileIds)
        logger.info(
            LogCategory.Settings,
            "Imported a settings file",
            LogField.Public("servers", parsed.servers.size.toString()),
            LogField.Public("profilesApplied", outcome.applied.toString()),
            LogField.Public("profilesSkipped", outcome.skipped.toString()),
        )
        SettingsImport(
            serverUrls = parsed.servers.map(ServerDocument::baseUrl).filter(String::isNotBlank).distinct(),
            exportedAt = parsed.exportedAt?.let { stamp -> runCatching { Instant.parse(stamp) }.getOrNull() },
            profilePreferencesApplied = outcome.applied,
            profilePreferencesSkipped = outcome.skipped,
        )
    }

    /**
     * The accounts this device has, keyed the way a settings file keys them.
     *
     * Fixtures are kept out for the reason the class KDoc gives, and a profile whose server row has gone is
     * dropped rather than guessed at: there is no honest address to key it by.
     */
    private suspend fun currentProfileIds(): Map<ProfileKey, String> {
        val byServerId = profiles.observeServers().first()
            .filterNot(ServerEntity::isFixture)
            .associateBy(ServerEntity::serverId)
        return profiles.observeProfiles().first()
            .filterNot(ProfileEntity::isFixture)
            .mapNotNull { profile -> byServerId[profile.serverId]?.let { it.keyFor(profile) to profile.profileId } }
            .toMap()
    }

    private fun ServerEntity.keyFor(profile: ProfileEntity) =
        ProfileKey(serverUrl = baseUrl, username = profile.username)

    private fun parse(document: String): SettingsDocument = try {
        SettingsTransfer.JSON.decodeFromString<SettingsDocument>(document)
    } catch (failure: SerializationException) {
        // Translated here rather than allowed to reach `resultOf`'s default: "that file is not a settings
        // export" is something the user can act on, and the parser's own message names JSON internals that
        // they cannot. `resultOf` is still the exception boundary; this only chooses the sentence.
        throw NotASettingsFile(failure)
    }

    /**
     * PRODUCT_SPEC 14.1 — the two ways this can fail, as the two errors that mean different things.
     *
     * [AppError.Validation] is "you picked the wrong file", which the user fixes by picking another.
     * Anything else is [AppError.Storage] — the document could not be read or written — which they cannot.
     */
    private fun asAppError(throwable: Throwable): AppError = when (throwable) {
        is NotASettingsFile -> AppError.Validation(summary = NotASettingsFile.MESSAGE)
        else -> AppError.Storage(summary = "The settings file could not be read or written.")
    }

    private class NotASettingsFile(cause: Throwable) : IllegalArgumentException(MESSAGE, cause) {
        companion object {
            const val MESSAGE = "That file is not a BookWave settings export."
        }
    }

    private companion object {
        private val FILE_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
    }
}
