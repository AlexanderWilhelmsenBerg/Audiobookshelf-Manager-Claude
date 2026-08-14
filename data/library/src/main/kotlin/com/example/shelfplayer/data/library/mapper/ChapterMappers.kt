package com.example.shelfplayer.data.library.mapper

import com.example.shelfplayer.core.database.entity.ChapterEntity
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.library.Chapter
import kotlin.time.Duration.Companion.milliseconds

/**
 * PRODUCT_SPEC PLAY-003 — a stored chapter row, back as the domain type.
 *
 * The inverse of `EntityMappers.chapterEntity`, and in its own file rather than beside it because
 * `EntityMappers` is at detekt's function limit and because the two directions have different readers:
 * everything there is written during a sync, and this is read by the Android Auto browse tree, which has no
 * session to get chapters from and must not open one.
 *
 * The book id is passed in rather than parsed out of `bookKey`: the key is a composite this mapper does not
 * own the format of, and the caller always knows which book it asked for.
 */
internal fun ChapterEntity.toDomain(bookId: LibraryItemId) = Chapter(
    serverId = ServerId(serverId),
    bookId = bookId,
    index = chapterIndex,
    title = title,
    start = startMillis.milliseconds,
    end = endMillis.milliseconds,
)
