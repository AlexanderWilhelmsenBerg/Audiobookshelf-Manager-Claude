package com.example.shelfplayer.data.auth

import com.example.shelfplayer.core.database.converter.StringListConverters
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.database.entity.ServerEntity
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ServerCapabilities
import com.example.shelfplayer.core.model.ServerCapability
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.ServerProbe
import com.example.shelfplayer.core.model.auth.LibraryAccess
import java.time.Instant

/**
 * PRODUCT_SPEC 9.3 — where `:data:auth`'s entities and domain models meet.
 *
 * Separate from `:data:library`'s `EntityMappers` because that one is `internal` to its module, and a
 * shared mapper in `:core:database` would put domain types on the database module's classpath. Two
 * small mappers is the cost of the boundary; the alternative is a mapper module that both depend on,
 * which is more structure than four functions justify.
 */
internal object AuthEntityMappers {

    /**
     * A server row for a URL never seen before.
     *
     * The capability columns are left empty rather than guessed. PRODUCT_SPEC SYNC-001 requires an
     * unprobed capability to read as unsupported, and `capabilitiesDetectedAt = null` is what lets the
     * UI tell "no handshake has run" apart from "the handshake found nothing".
     */
    fun newServerEntity(serverId: ServerId, baseUrl: String, probe: ServerProbe?, fetchedAt: Instant) = ServerEntity(
        serverId = serverId.value,
        displayName = displayNameFor(baseUrl),
        baseUrl = baseUrl,
        detectedVersion = probe?.serverVersion,
        isFixture = false,
        lastFetchedAt = fetchedAt.toEpochMilli(),
        authMethodsJson = StringListConverters.fromStringList(probe?.authMethods.orEmpty()),
        capabilitiesJson = StringListConverters.fromStringList(emptyList()),
        capabilitiesDetectedAt = null,
    )

    /**
     * The name shown in the profile switcher (PRODUCT_SPEC AUTH-002).
     *
     * The host, with the scheme and any port stripped. A server does not tell us what it is called, and
     * the address is the only thing the user typed — inventing "My Server" would be less recognisable
     * than the thing they recognise. Treated as untrusted input (PRODUCT_SPEC 22.20): it is only ever
     * displayed, never parsed for meaning.
     */
    fun displayNameFor(baseUrl: String): String = baseUrl
        .substringAfter("://")
        .substringBefore('/')
        .substringBefore(':')
        .ifBlank { baseUrl }

    /**
     * PRODUCT_SPEC SYNC-001 — a stored capability name this build does not recognise is dropped.
     *
     * The same rule as on the wire, applied on the way out of the database, and it matters for the same
     * reason: a downgrade — an app update rolled back, a name removed from the enum — must not turn an
     * unreadable name into an enabled feature.
     */
    fun toCapabilities(entity: ServerEntity) = ServerCapabilities(
        serverId = ServerId(entity.serverId),
        serverVersion = entity.detectedVersion,
        supported = StringListConverters.toStringList(entity.capabilitiesJson)
            .mapNotNull { name -> ServerCapability.entries.firstOrNull { it.name == name } }
            .toSet(),
        authMethods = StringListConverters.toStringList(entity.authMethodsJson),
    )

    /** Sorted, so two handshakes that found the same capabilities produce the same stored bytes. */
    fun capabilitiesJson(capabilities: Set<ServerCapability>): String =
        StringListConverters.fromStringList(capabilities.map(ServerCapability::name).sorted())

    fun authMethodsJson(authMethods: List<String>): String = StringListConverters.fromStringList(authMethods)

    fun toEntity(profile: Profile, remoteUserId: String?, access: LibraryAccess) = ProfileEntity(
        profileId = profile.id.value,
        serverId = profile.serverId.value,
        remoteUserId = remoteUserId,
        username = profile.username,
        displayName = profile.displayName,
        role = profile.role.name,
        requiresReauthentication = profile.requiresReauthentication,
        lastUsedAt = profile.lastUsedAt?.toEpochMilli(),
        isFixture = profile.isFixture,
        accessibleLibrariesJson = accessibleLibrariesJson(access),
        hasAllLibraryAccess = access.hasAllLibraryAccess,
        hasAllTagAccess = access.hasAllTagAccess,
    )

    /**
     * PRODUCT_SPEC 5.2 — the stored grant, read back.
     *
     * A blank id in the stored list is dropped rather than turned into a `LibraryId`, whose constructor
     * rejects one. A corrupt row must degrade to a narrower grant, never to a crash on every sync.
     */
    fun toLibraryAccess(entity: ProfileEntity) = LibraryAccess(
        hasAllLibraryAccess = entity.hasAllLibraryAccess,
        accessibleLibraryIds = StringListConverters.toStringList(entity.accessibleLibrariesJson)
            .filter(String::isNotBlank)
            .map(::LibraryId),
        hasAllTagAccess = entity.hasAllTagAccess,
    )

    fun accessibleLibrariesJson(access: LibraryAccess): String =
        StringListConverters.fromStringList(access.accessibleLibraryIds.map(LibraryId::value))
}
