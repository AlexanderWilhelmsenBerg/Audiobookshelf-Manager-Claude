package com.example.shelfplayer.core.database.converter

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Stores short string lists (narrators, genres, tags) as a JSON array.
 *
 * A delimiter-joined string would be smaller but is wrong: a narrator credited as "Smith, John" or a
 * tag containing a separator would silently split into two values. JSON round-trips exactly, which
 * matters because these values are shown to the user and searched against (PRODUCT_SPEC LIB-002).
 *
 * Genres and tags get their own tables when PRODUCT_SPEC 13's `GenreEntity`/`TagEntity` browsing
 * lands in Phase 1; the migration to that shape is a data move, not a format guess, precisely
 * because the stored form is unambiguous.
 */
object StringListConverters {
    private val json = Json { encodeDefaults = true }
    private val serializer = ListSerializer(String.serializer())

    @TypeConverter
    @JvmStatic
    fun fromStringList(values: List<String>): String = json.encodeToString(serializer, values)

    @TypeConverter
    @JvmStatic
    fun toStringList(encoded: String): List<String> =
        if (encoded.isEmpty()) emptyList() else json.decodeFromString(serializer, encoded)
}
