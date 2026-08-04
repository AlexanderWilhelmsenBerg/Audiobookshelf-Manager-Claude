package com.example.shelfplayer.core.database.entity

/**
 * PRODUCT_SPEC 13.1 — remote ids are only unique within one server.
 *
 * Room's `@Relation` can join on a single column only, so the compound identity
 * `(serverId, remoteId)` is materialised as one deterministic key column. Both parts are *also*
 * stored as their own columns on every entity, so queries can still filter by server and a future
 * migration can rebuild the key without losing information.
 *
 * The separator is ASCII Unit Separator, which cannot appear in a URL-safe identifier. [of] rejects
 * a value that contains it rather than producing a key that could collide with a different pair.
 */
object EntityKey {
    private const val SEPARATOR = '\u001F'

    fun of(serverId: String, remoteId: String): String {
        requireUsable(serverId)
        requireUsable(remoteId)
        return "$serverId$SEPARATOR$remoteId"
    }

    /** Scopes a per-profile row, e.g. one profile's progress on one book. */
    fun scoped(profileId: String, entityKey: String): String {
        requireUsable(profileId)
        require(entityKey.isNotBlank()) { "Entity key must not be blank" }
        return "$profileId$SEPARATOR$entityKey"
    }

    /** Appends a positional discriminator, e.g. a track index within a book. */
    fun indexed(entityKey: String, index: Int): String {
        require(entityKey.isNotBlank()) { "Entity key must not be blank" }
        return "$entityKey$SEPARATOR$index"
    }

    /**
     * The remote id encoded in a key produced by [of].
     *
     * Exists so no other module has to know the separator, which is what keeps the key format a
     * detail of `:core:database` and changeable by a migration.
     */
    fun remoteIdOf(key: String): String = key.substringAfterLast(SEPARATOR)

    /** The server id encoded in a key produced by [of]. */
    fun serverIdOf(key: String): String = key.substringBefore(SEPARATOR)

    private fun requireUsable(identifier: String) {
        require(identifier.isNotBlank()) { "Identifier must not be blank" }
        require(!identifier.contains(SEPARATOR)) {
            "Identifier must not contain the entity-key separator"
        }
    }
}
