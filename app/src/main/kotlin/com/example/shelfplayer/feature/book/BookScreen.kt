package com.example.shelfplayer.feature.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.core.designsystem.component.ShelfEmptyState
import com.example.shelfplayer.core.designsystem.component.ShelfLoadingState
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.LocalAvailability
import com.example.shelfplayer.feature.browse.BookCover
import com.example.shelfplayer.feature.player.PlayerViewModel
import com.example.shelfplayer.playback.PlaybackUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration

@Composable
fun BookRoute(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BookViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playback by playerViewModel.playback.collectAsStateWithLifecycle()
    BookScreen(
        uiState = uiState,
        playback = playback,
        onPlay = playerViewModel::onPlay,
        onTogglePlayPause = playerViewModel::onTogglePlayPause,
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(
    uiState: BookUiState,
    playback: PlaybackUiState,
    onPlay: (LibraryItemId) -> Unit,
    onTogglePlayPause: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
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

            is BookUiState.Loaded -> BookDetails(
                book = uiState.book,
                playback = playback,
                onPlay = onPlay,
                onTogglePlayPause = onTogglePlayPause,
                modifier = content,
            )
        }
    }
}

@Composable
private fun BookDetails(
    book: Book,
    playback: PlaybackUiState,
    onPlay: (LibraryItemId) -> Unit,
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // PRODUCT_SPEC LIB-004 / PLAY-001 — the cover, with the two actions beside it.
        //
        // Top-**right** of the cover rather than under the metadata: play is what this screen is for,
        // and a button below a description is a button the reader has to scroll to on any book whose
        // description is longer than a paragraph.
        BookHeader(
            book = book,
            playback = playback,
            onPlay = onPlay,
            onTogglePlayPause = onTogglePlayPause,
        )

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
            text = pluralStringResource(R.plurals.book_tracks, book.trackCount, book.trackCount),
            style = MaterialTheme.typography.bodyMedium,
        )

        ProgressSummary(book = book)

        Availability(book = book)

        BookLabels(book = book)

        PublicationFacts(book = book)

        book.description?.let { description ->
            Text(
                // PRODUCT_SPEC LIB-004: HTML descriptions are sanitized before rendering. Phase 0
                // strips markup rather than rendering it; a real sanitizing renderer arrives with
                // the metadata editor in Phase 5, where untrusted provider content also lands.
                text = description.stripHtml(),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * PRODUCT_SPEC LIB-004 / PLAY-001 — the cover, and the two things you can do with the book.
 *
 * The actions sit at the **top right of the cover**, in the order a hand reaches them: download on the
 * left, play on the right, play being the one pressed every time and download the one pressed once.
 *
 * Download is a placeholder and says so: it is disabled, and its content description names the phase it
 * arrives in rather than implying a button that silently does nothing. A control that looks live and is
 * not is worse than one that admits it (PRODUCT_SPEC 21).
 */
@Composable
private fun BookHeader(
    book: Book,
    playback: PlaybackUiState,
    onPlay: (LibraryItemId) -> Unit,
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BookCover(book = book, modifier = Modifier.width(COVER_WIDTH))
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(onClick = {}, enabled = false) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = stringResource(R.string.book_download_later),
                    )
                }
                PlayIconButton(
                    book = book,
                    playback = playback,
                    onPlay = onPlay,
                    onTogglePlayPause = onTogglePlayPause,
                )
            }
        }
    }
}

/**
 * PRODUCT_SPEC PLAY-001 — one icon button, whose meaning depends on what the session is already doing.
 *
 * Three states rather than a separate play and pause control:
 *
 *  - **this** book is playing — pause it;
 *  - **this** book is loaded but paused — resume it, without opening a second session;
 *  - anything else — open a session for this book.
 *
 * The middle case is the one worth spelling out. Pressing play again on a book already in the player
 * would otherwise ask the server for a new session, which records a second listening entry for one
 * uninterrupted listen and throws away the buffer.
 *
 * The label says where it will resume from when the book has a stored position, because "Play" on a
 * book you are eight hours into does not say what is about to happen.
 */
@Composable
private fun PlayIconButton(
    book: Book,
    playback: PlaybackUiState,
    onPlay: (LibraryItemId) -> Unit,
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isCurrent = playback.bookId == book.id
    val isPlayingThis = isCurrent && playback.isPlaying
    val resumeAt = book.progress?.position?.takeIf { it > Duration.ZERO && book.progress?.isFinished != true }
    val startingLabel = stringResource(R.string.player_starting)
    FilledIconButton(
        onClick = { if (isCurrent) onTogglePlayPause() else onPlay(book.id) },
        enabled = !playback.isLoading,
        modifier = modifier,
    ) {
        if (playback.isLoading) {
            // The spinner replaces the icon, so it has to carry the icon's label — a progress indicator
            // with no description announces nothing at all to a screen reader.
            CircularProgressIndicator(
                modifier = Modifier
                    .size(20.dp)
                    .semantics { contentDescription = startingLabel },
            )
        } else {
            Icon(
                imageVector = if (isPlayingThis) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                // An icon-only control carries its whole label here, so the description says what will
                // happen — including *where it will resume from* on a part-finished book, which the icon
                // cannot show and which is the one thing a listener wants confirmed before pressing.
                contentDescription = when {
                    isPlayingThis -> stringResource(R.string.player_pause)
                    isCurrent -> stringResource(R.string.player_resume)
                    resumeAt != null -> stringResource(R.string.player_resume_at, resumeAt.formatted())
                    else -> stringResource(R.string.player_play)
                },
            )
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

/**
 * PRODUCT_SPEC LIB-004 — remote availability, shown independently of local availability.
 *
 * Two chips rather than one, because they answer different questions and a single "Downloaded" label
 * silently answers only the second. A book reaches this screen only while the last sync still listed
 * it, so the remote chip is qualified by *when* that was: "on the server" with no date behind it is a
 * claim about right now that a cached row cannot make.
 */
@Composable
private fun Availability(book: Book, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(text = stringResource(R.string.book_on_server)) },
            )
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(text = stringResource(book.localAvailability.labelRes())) },
            )
        }
        Text(
            text = stringResource(R.string.book_last_checked, book.lastFetchedAt.asDate()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** PRODUCT_SPEC LIB-004 — genres and tags, which the sync has always stored and no screen has shown. */
@Composable
private fun BookLabels(book: Book, modifier: Modifier = Modifier) {
    val labels = book.genres + book.tags
    if (labels.isEmpty()) return
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEach { label ->
            SuggestionChip(onClick = {}, enabled = false, label = { Text(text = label) })
        }
    }
}

/**
 * PRODUCT_SPEC LIB-004 / LIB-002 — publisher, year, language, size and the two identifiers.
 *
 * Every row is omitted when its field is absent rather than rendered with a dash. Most self-hosted
 * items carry almost none of these, and a detail screen of eight "—" rows tells the user less than a
 * short one does.
 */
@Composable
private fun PublicationFacts(book: Book, modifier: Modifier = Modifier) {
    val facts = buildList {
        book.publishedYear?.let { add(R.string.book_published to it.toString()) }
        book.publisher?.let { add(R.string.book_publisher to it) }
        book.language?.let { add(R.string.book_language to it) }
        book.isbn?.let { add(R.string.book_isbn to it) }
        book.asin?.let { add(R.string.book_asin to it) }
        book.sizeBytes.takeIf { it > 0 }?.let { add(R.string.book_size to it.asFileSize()) }
    }
    if (facts.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        facts.forEach { (labelRes, value) ->
            Text(
                text = stringResource(labelRes, value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun LocalAvailability.labelRes(): Int = when (this) {
    LocalAvailability.NotDownloaded -> R.string.book_not_downloaded
    LocalAvailability.Partial -> R.string.book_download_partial
    LocalAvailability.Complete -> R.string.book_downloaded
}

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter
    .ofLocalizedDate(FormatStyle.MEDIUM)
    .withZone(ZoneId.systemDefault())

private fun Instant.asDate(): String = DATE_FORMAT.format(this)

/**
 * `1.4 GB`, `320 MB`, `48 kB`.
 *
 * Decimal units, matching what a download manager and a server's own storage figure report. Binary
 * units would render the same file as a different number to the one the server shows, and a user
 * comparing the two would reasonably conclude the app had the wrong file.
 */
private fun Long.asFileSize(): String {
    val units = listOf("GB" to GIGABYTE, "MB" to MEGABYTE, "kB" to KILOBYTE)
    val match = units.firstOrNull { this >= it.second }
        ?: return String.format(Locale.getDefault(), "%d B", this)
    return String.format(Locale.getDefault(), "%.1f %s", this.toDouble() / match.second, match.first)
}

private const val KILOBYTE = 1_000L
private const val MEGABYTE = 1_000_000L
private const val GIGABYTE = 1_000_000_000L

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

/** Wide enough to read a cover's title at arm's length, narrow enough to leave room for the actions. */
private val COVER_WIDTH = 140.dp
