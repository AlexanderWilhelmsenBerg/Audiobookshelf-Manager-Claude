package com.example.shelfplayer.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.example.shelfplayer.R
import com.example.shelfplayer.core.designsystem.theme.BookWaveDesign
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.core.model.SeriesSequence
import com.example.shelfplayer.core.model.library.Series
import com.example.shelfplayer.core.model.library.SeriesMembership
import com.example.shelfplayer.domain.library.SeriesOrder
import com.example.shelfplayer.domain.library.SeriesShelf
import com.example.shelfplayer.domain.usecase.SeriesOrderOwner
import com.example.shelfplayer.feature.browse.CoverUrls
import com.example.shelfplayer.feature.browse.LocalCoverUrls
import com.example.shelfplayer.feature.series.SeriesEditorActions
import com.example.shelfplayer.feature.series.SeriesEditorState
import com.example.shelfplayer.feature.series.SeriesScreen
import com.example.shelfplayer.feature.series.SeriesUiState

@Preview(name = "Series editor", showSystemUi = true)
@Composable
fun SeriesEditorPreview() {
    var shelf by remember {
        mutableStateOf(
            run {
                val sample = DesignPreviewFixtures.home.books
                val series = Series(sample.first().serverId, SeriesId("voyage"), "The Long Voyage")
                val positions = listOf("1", "2", "3", "5")
                SeriesShelf(
                    series,
                    sample.zip(positions) { book, position ->
                        val membership = SeriesMembership(series, SeriesSequence.parse(position), isPrimary = true)
                        book.copy(seriesMemberships = listOf(membership))
                    },
                )
            },
        )
    }
    var missing by remember { mutableStateOf(emptySet<Int>()) }
    fun draft() =
        SeriesEditorState(SeriesOrderOwner(ProfileId("preview"), 0), SeriesOrder.from(shelf, missing), missing)
    var editor by remember { mutableStateOf<SeriesEditorState?>(draft()) }
    val cover = "android.resource://${LocalContext.current.packageName}/${R.drawable.design_preview_cover}"
    PreviewTheme(design = BookWaveDesign.Editorial) {
        CompositionLocalProvider(
            LocalCoverUrls provides CoverUrls { book ->
                cover.takeIf { book.coverPath != null }
            },
        ) {
            SeriesScreen(
                uiState = SeriesUiState(
                    shelf = shelf,
                    isLoading = false,
                    canEdit = true,
                    editor = editor,
                    missingBooks = missing,
                ),
                onBookSelected = {},
                onBookPlaySelected = {},
                onNavigateUp = {},
                editorActions = SeriesEditorActions(
                    start = { editor = draft() },
                    cancel = { editor = null },
                    undo = { editor = editor?.undo() },
                    move = { from, to, move -> editor = editor?.let { it.edit(it.order.move(from, to, move)) } },
                    insertMissing = { n -> editor = editor?.let { it.edit(it.order.insertMissing(n)) } },
                    removeMissing = { n -> editor = editor?.let { it.edit(it.order.removeMissing(n)) } },
                    ungroup = { key -> editor = editor?.let { it.edit(it.order.ungroup(key)) } },
                    save = {
                        editor?.order?.let { order ->
                            val changes = order.changes.associate { it.bookId to SeriesSequence.parse(it.after) }
                            val books = shelf.books.map { book ->
                                changes[book.id]?.let { seq ->
                                    book.copy(
                                        seriesMemberships = book.seriesMemberships.map {
                                            it.copy(sequence = seq)
                                        },
                                    )
                                } ?: book
                            }
                            shelf = shelf.copy(books = books.sortedBy { it.seriesMemberships.first().sequence })
                            missing = order.missingBooks
                        }
                        editor = null
                    },
                ),
            )
        }
    }
}
