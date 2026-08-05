package com.example.shelfplayer.data.auth

import com.example.shelfplayer.core.database.converter.StringListConverters
import com.example.shelfplayer.core.database.entity.ProfileEntity
import com.example.shelfplayer.core.database.entity.ServerEntity
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.ServerProbe
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

    fun toEntity(profile: Profile, remoteUserId: String?) = ProfileEntity(
        profileId = profile.id.value,
        serverId = profile.serverId.value,
        remoteUserId = remoteUserId,
        username = profile.username,
        displayName = profile.displayName,
        role = profile.role.name,
        requiresReauthentication = profile.requiresReauthentication,
        lastUsedAt = profile.lastUsedAt?.toEpochMilli(),
        isFixture = profile.isFixture,
    )
}
