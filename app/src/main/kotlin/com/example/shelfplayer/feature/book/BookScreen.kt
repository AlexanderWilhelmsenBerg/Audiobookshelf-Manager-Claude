package com.example.shelfplayer.feature.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.core.designsystem.component.ShelfEmptyState
import com.example.shelfplayer.core.designsystem.component.ShelfLoadingState
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.LocalAvailability
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration

@Composable
fun BookRoute(onNavigateUp: () -> Unit, modifier: Modifier = Modifier, viewModel: BookViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    BookScreen(uiState = uiState, onNavigateUp = onNavigateUp, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(uiState: BookUiState, onNavigateUp: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (uiState) {
                            is BookUiState.Loaded -> uiState.book.title
                            BookUiState.Loading,
                            BookUiState.Missing,
                            -> stringResource(R.string.home_title)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val content = Modifier.fillMaxSize().padding(innerPadding)
        when (uiState) {
            BookUiState.Loading -> ShelfLoadingState(
                label = stringResource(R.string.book_loading),
                modifier = content,
            )

            BookUiState.Missing -> ShelfEmptyState(
                title = stringResource(R.string.book_missing_title),
                body = stringResource(R.string.book_missing_body),
                modifier = content,
            )

            is BookUiState.Loaded -> BookDetails(book = uiState.book, modifier = content)
        }
    }
}

@Composable
private fun BookDetails(book: Book, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        book.subtitle?.let { subtitle ->
            Text(text = subtitle, style = MaterialTheme.typography.titleSmall)
        }
        if (book.authors.isNotEmpty()) {
            Text(
                text = book.authors.joinToString { it.name },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (book.narrators.isNotEmpty()) {
            Text(
                text = stringResource(R.string.book_narrated_by, book.narrators.joinToString()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        book.seriesMemberships.forEach { membership ->
            Text(
                text = stringResource(
                    R.string.book_series_position,
                    membership.series.name,
                    membership.sequence.raw.ifEmpty { "—" },
                ),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Text(
            text = stringResource(R.string.book_duration, book.duration.formatted()),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.book_tracks, book.trackCount),
            style = MaterialTheme.typography.bodyMedium,
        )

        ProgressSummary(book = book)

        AssistChip(
            onClick = {},
            enabled = false,
            label = { Text(text = stringResource(book.localAvailability.labelRes())) },
        )

        book.description?.let { description ->
            Text(
                // PRODUCT_SPEC LIB-004: HTML descriptions are sanitized before rendering. Phase 0
                // strips markup rather than rendering it; a real sanitizing renderer arrives with
                // the metadata editor in Phase 5, where untrusted provider content also lands.
                text = description.stripHtml(),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.book_playback_unavailable))
        }
    }
}

@Composable
private fun ProgressSummary(book: Book, modifier: Modifier = Modifier) {
    val progress = book.progress ?: return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val percent = (progress.fractionComplete * PERCENT).roundToInt()
        Text(
            text = if (progress.isFinished) {
                stringResource(R.string.book_finished)
            } else {
                stringResource(R.string.book_progress, percent)
            },
            style = MaterialTheme.typography.labelLarge,
        )
        LinearProgressIndicator(
            progress = { progress.fractionComplete },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val PERCENT = 100

private fun LocalAvailability.labelRes(): Int = when (this) {
    LocalAvailability.NotDownloaded -> R.string.book_not_downloaded
    LocalAvailability.Partial -> R.string.book_download_partial
    LocalAvailability.Complete -> R.string.book_downloaded
}

/**
 * Removes tags from a description before it is displayed.
 *
 * This is a display-time guard, not the sanitizer PRODUCT_SPEC LIB-004 ultimately needs: it strips
 * markup rather than allow-listing it, which is safe but lossy. It is here so that no build of this
 * app ever renders server-supplied markup unfiltered.
 */
private fun String.stripHtml(): String = replace(Regex("<[^>]*>"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun Duration.formatted(): String {
    val totalMinutes = inWholeMinutes
    val hours = totalMinutes / MINUTES_PER_HOUR
    val minutes = totalMinutes % MINUTES_PER_HOUR
    return if (hours > 0) {
        String.format(Locale.ROOT, "%dh %02dm", hours, minutes)
    } else {
        String.format(Locale.ROOT, "%dm", minutes)
    }
}

private const val MINUTES_PER_HOUR = 60
