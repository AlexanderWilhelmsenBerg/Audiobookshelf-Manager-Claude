package com.example.shelfplayer.core.model

/**
 * PRODUCT_SPEC 13.1 — remote identifiers are never globally unique.
 *
 * Every remote entity is addressed by `(serverId, remoteId)`, and user-specific state adds a
 * [ProfileId]. Wrapping the raw strings in value classes means a library id can never be passed
 * where an item id is expected.
 */
@JvmInline
value class ServerId(val value: String) {
    init {
        require(value.isNotBlank()) { "ServerId must not be blank" }
    }
}

/** PRODUCT_SPEC AUTH-002 — stable local id, independent of the server username. */
@JvmInline
value class ProfileId(val value: String) {
    init {
        require(value.isNotBlank()) { "ProfileId must not be blank" }
    }
}

@JvmInline
value class LibraryId(val value: String) {
    init {
        require(value.isNotBlank()) { "LibraryId must not be blank" }
    }
}

@JvmInline
value class LibraryItemId(val value: String) {
    init {
        require(value.isNotBlank()) { "LibraryItemId must not be blank" }
    }
}

@JvmInline
value class AuthorId(val value: String) {
    init {
        require(value.isNotBlank()) { "AuthorId must not be blank" }
    }
}

@JvmInline
value class SeriesId(val value: String) {
    init {
        require(value.isNotBlank()) { "SeriesId must not be blank" }
    }
}
