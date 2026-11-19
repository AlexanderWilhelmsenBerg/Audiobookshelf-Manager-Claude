package com.example.shelfplayer.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.example.shelfplayer.core.designsystem.theme.BookWaveDesign
import com.example.shelfplayer.core.designsystem.theme.ShelfPlayerTheme
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.core.model.SeriesSequence
import com.example.shelfplayer.core.model.library.Series
import com.example.shelfplayer.core.model.library.SeriesMembership
import com.example.shelfplayer.domain.library.SeriesOrder
import com.example.shelfplayer.domain.library.SeriesShelf
import com.example.shelfplayer.domain.usecase.SeriesOrderOwner
import com.example.shelfplayer.feature.series.SeriesEditorActions
import com.example.shelfplayer.feature.series.SeriesEditorState
import com.example.shelfplayer.feature.series.SeriesScreen
import com.example.shelfplayer.feature.series.SeriesUiState

@Preview(name = "Series editor", showSystemUi = true)
@Composable
fun SeriesEditorPreview() {
    val shelf = remember {
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
    }
    val missing = setOf(4)
    var editor by remember {
        mutableStateOf(
            SeriesEditorState(SeriesOrderOwner(ProfileId("preview"), 0), SeriesOrder.from(shelf, missing), missing),
        )
    }
    ShelfPlayerTheme(darkTheme = isSystemInDarkTheme(), design = BookWaveDesign.Editorial) {
        SeriesScreen(
            uiState = SeriesUiState(shelf = shelf, isLoading = false, canEdit = true, editor = editor),
            onBookSelected = {},
            onBookPlaySelected = {},
            onNavigateUp = {},
            editorActions = SeriesEditorActions(
                move = { from, to, move -> editor = editor.copy(order = editor.order.move(from, to, move)) },
                addMissing = { editor = editor.copy(order = editor.order.addMissing(it)) },
                removeMissing = { editor = editor.copy(order = editor.order.removeMissing(it)) },
            ),
        )
    }
}
