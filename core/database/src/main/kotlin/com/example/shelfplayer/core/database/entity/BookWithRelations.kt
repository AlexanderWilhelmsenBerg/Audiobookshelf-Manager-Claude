package com.example.shelfplayer.core.database.entity

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

/**
 * A book with everything needed to render its detail screen and to build a playback queue.
 *
 * Progress is deliberately absent. `@Relation` cannot be parameterised, so including it would load
 * every profile's progress and leave the filtering to a mapper — a profile boundary enforced by
 * convention instead of by a query (PRODUCT_SPEC 5.2). The repository combines this with a
 * profile-scoped progress query instead.
 */
data class BookWithRelations(
    @Embedded val book: BookEntity,
    @Relation(
        parentColumn = "bookKey",
        entityColumn = "authorKey",
        associateBy = Junction(
            value = BookAuthorCrossRef::class,
            parentColumn = "bookKey",
            entityColumn = "authorKey",
        ),
    )
    val authors: List<AuthorEntity>,
    @Relation(
        entity = BookSeriesCrossRef::class,
        parentColumn = "bookKey",
        entityColumn = "bookKey",
    )
    val seriesMemberships: List<SeriesMembershipWithSeries>,
    @Relation(parentColumn = "bookKey", entityColumn = "bookKey")
    val tracks: List<AudioTrackEntity>,
    @Relation(parentColumn = "bookKey", entityColumn = "bookKey")
    val chapters: List<ChapterEntity>,
)

data class SeriesMembershipWithSeries(
    @Embedded val membership: BookSeriesCrossRef,
    @Relation(parentColumn = "seriesKey", entityColumn = "seriesKey")
    val series: SeriesEntity,
)
