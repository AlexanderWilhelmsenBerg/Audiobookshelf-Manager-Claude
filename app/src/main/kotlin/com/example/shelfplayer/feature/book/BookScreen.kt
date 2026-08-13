package com.example.shelfplayer.feature.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
        onFinishedChanged = viewModel::onFinishedChanged,
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
    onFinishedChanged: (Boolean) -> Unit,
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
                onFinishedChanged = onFinishedChanged,
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
    onFinishedChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // PRODUCT_SPEC LIB-004 — the hero: cover, title, author, and the two actions.
        //
        // Everything a reader came for is above the fold and in one block, rather than a cover followed
        // by a stack of one-line facts. The block that used to be here read as a form because every
        // field had the same weight; hierarchy is what makes it a book instead of a record.
        BookHeader(
            book = book,
            playback = playback,
            onPlay = onPlay,
            onTogglePlayPause = onTogglePlayPause,
        )

        // Length, tracks and availability as one quiet strip, not three sentences. Facts of the same
        // kind belong on the same line, and a reader scans a strip faster than they read a list.
        FactStrip(book = book)

        ProgressSummary(
            book = book,
            onFinishedChanged = onFinishedChanged,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        book.description?.let { description ->
            Section(titleRes = R.string.book_section_about) {
                Synopsis(text = description.stripHtml())
            }
        }

        BookLabels(book = book)

        PublicationFacts(book = book)

        // PRODUCT_SPEC LIB-004 — "on the server, as of when". Demoted from a chip pair to a footnote,
        // because that is what it is: a caveat about how fresh this screen's contents are, not a fact
        // about the book. It stays because a cached row must never imply "on the server right now".
        Text(
            text = stringResource(R.string.book_last_checked, book.lastFetchedAt.asDate()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * PRODUCT_SPEC LIB-004 — the synopsis, three lines at a time.
 *
 * A publisher's blurb runs to two or three hundred words, and printed in full it pushes the genres, the
 * publication facts and the freshness footnote off the bottom of a phone screen. Three lines is enough to
 * recognise a book you half-remember, which is what somebody on this screen is usually doing; the rest is
 * one tap away for the one time in ten they want it.
 *
 * **The button is only rendered when it would do something.** Compose can only know whether the text
 * overflowed *after* it has been laid out, so the overflow is captured from the layout result rather than
 * guessed from the string's length — a two-line blurb on a phone can be four on a small screen at large
 * font sizes, and a "Show more" that expands nothing is worse than none.
 */
@Composable
private fun Synopsis(text: String, modifier: Modifier = Modifier) {
    var isExpanded by rememberSaveable(text) { mutableStateOf(false) }
    var isTruncated by remember(text) { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            // PRODUCT_SPEC LIB-004: HTML descriptions are sanitized before rendering. The caller strips
            // markup rather than rendering it; a real sanitizing renderer arrives with the metadata editor
            // in Phase 5, where untrusted provider content also lands.
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (isExpanded) Int.MAX_VALUE else SYNOPSIS_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result -> isTruncated = result.hasVisualOverflow || isTruncated },
        )
        if (isTruncated) {
            TextButton(
                onClick = { isExpanded = !isExpanded },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
            ) {
                Text(
                    text = stringResource(
                        if (isExpanded) R.string.book_synopsis_less else R.string.book_synopsis_more,
                    ),
                )
            }
        }
    }
}

/** Enough to recognise a book by, which is what this screen is for. */
private const val SYNOPSIS_LINES = 3

/**
 * A titled block, so the screen reads as sections rather than as a run of paragraphs.
 *
 * The heading is what turns "some text" into "About this book" — a reader skimming for the description
 * finds it by its label, and one skipping it knows what they are skipping.
 */
@Composable
private fun Section(titleRes: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium,
        )
        content()
    }
}

/**
 * PRODUCT_SPEC LIB-004 — length, tracks and whether it is downloaded, as one line.
 *
 * These were three separate sentences at body size, which gave a track count the same visual weight as
 * the book's title. They are metadata: one row, one type size down, separated by dots. The download
 * state joins them because "is it on this device" is the same kind of fact as "how long is it".
 */
@Composable
private fun FactStrip(book: Book, modifier: Modifier = Modifier) {
    val facts = buildList {
        add(stringResource(R.string.book_duration, book.duration.formatted()))
        add(pluralStringResource(R.plurals.book_tracks, book.trackCount, book.trackCount))
        add(stringResource(book.localAvailability.labelRes()))
    }
    Text(
        text = facts.joinToString(FACT_SEPARATOR),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

private const val FACT_SEPARATOR = "  ·  "

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
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Shadowed rather than flat. A cover that sits *on* the page instead of *in* it is most of the
        // difference between this reading as a book and as a database row.
        BookCover(
            book = book,
            modifier = Modifier
                .width(COVER_WIDTH)
                .shadow(elevation = 8.dp, shape = MaterialTheme.shapes.medium),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // The actions first, top-right of the cover as asked. `End` alignment on this one child
            // rather than on the column, so the text below it stays left-aligned and readable.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
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

            // Title, author, narrator, series — in the order a reader wants them, at descending weight.
            // Previously each was a separate paragraph at nearly the same size, which is why the screen
            // read as a form: nothing told the eye where to start.
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            book.subtitle?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (book.authors.isNotEmpty()) {
                Text(
                    text = book.authors.joinToString { it.name },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (book.narrators.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.book_narrated_by, book.narrators.joinToString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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

/**
 * PRODUCT_SPEC PLAY-004 — how far in, and the one control that says otherwise.
 *
 * ### The tick box is a defect fix
 *
 * A device run found a book marked finished by the 95% threshold with **no way to undo it**: every write
 * path or-ed the flag, so once set it was permanent and the book's progress could never be shown again.
 * PRODUCT_SPEC PLAY-004 says marking finished is explicit; nothing said un-marking it was impossible.
 *
 * It is a checkbox rather than a menu item because it is a two-state fact about the book, and because a
 * user who has just seen the wrong state wants to correct it in one tap from where they saw it.
 *
 * ### It renders with no progress row too
 *
 * A book nobody has opened has no progress, and marking it finished without listening to it is a real
 * thing people do with a book they read on paper. So the bar and the percentage are conditional, and the
 * control is not.
 */
@Composable
private fun ProgressSummary(book: Book, onFinishedChanged: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val progress = book.progress
    val isFinished = progress?.isFinished == true
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (progress != null) {
            val percent = (progress.fractionComplete * PERCENT).roundToInt()
            Text(
                text = if (isFinished) {
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = isFinished, role = Role.Checkbox, onValueChange = onFinishedChanged)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = isFinished, onCheckedChange = null)
            Text(
                text = stringResource(R.string.book_mark_finished),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private const val PERCENT = 100

/** PRODUCT_SPEC LIB-004 — genres and tags, which the sync has always stored and no screen has shown. */
@Composable
private fun BookLabels(book: Book, modifier: Modifier = Modifier) {
    val labels = book.genres + book.tags
    if (labels.isEmpty()) return
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
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
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
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
