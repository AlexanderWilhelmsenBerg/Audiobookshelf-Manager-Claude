package com.example.shelfplayer.feature.book

import android.content.Intent
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shelfplayer.R
import com.example.shelfplayer.core.designsystem.component.ShelfEmptyState
import com.example.shelfplayer.core.designsystem.component.ShelfLoadingState
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.LocalAvailability
import com.example.shelfplayer.feature.browse.BookCover
import com.example.shelfplayer.feature.player.HistorySheet
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
    onManageDownloads: () -> Unit,
    onEditMetadata: (LibraryItemId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BookViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playback by playerViewModel.playback.collectAsStateWithLifecycle()
    val menu by viewModel.menu.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val embed by viewModel.embed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    BookScreen(
        uiState = uiState,
        playback = playback,
        menu = menu,
        message = message,
        onMessageShown = viewModel::onMessageShown,
        embed = embed,
        onEmbedStatusShown = viewModel::onEmbedStatusShown,
        actions = BookActions(
            onPlay = playerViewModel::onPlay,
            onTogglePlayPause = playerViewModel::onTogglePlayPause,
            onFinishedChanged = viewModel::onFinishedChanged,
            onDiscardProgress = viewModel::onDiscardProgress,
            // The browser, not a WebView. This is the user's own server in their own session, and a WebView
            // would ask them to sign in again inside an app that already holds a token it must not hand over.
            onOpenWebClient = { url ->
                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            },
            onDownloadClicked = viewModel::onDownloadClicked,
            onRemoveDownload = viewModel::onRemoveDownload,
            onManageDownloads = onManageDownloads,
            onEditMetadata = onEditMetadata,
            onRemoveFromServer = viewModel::onRemoveFromServer,
            onEmbedMetadata = viewModel::onEmbedMetadata,
        ),
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(
    uiState: BookUiState,
    playback: PlaybackUiState,
    menu: BookMenuState,
    actions: BookActions,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * PRODUCT_SPEC 21 — the one-line reason an action did not happen, or `null`.
     *
     * Every refusal on this screen is silent without it, and the download button has three that a user will
     * actually meet: no space, a permission the server revoked, and a book whose files the catalogue does
     * not know about. A control that appears to do nothing is the worst of the available outcomes.
     */
    message: BookMessage? = null,
    onMessageShown: () -> Unit = {},
    /** PRODUCT_SPEC MGR-007 — where an embed has got to. [EmbedStatus.Idle] draws nothing. */
    embed: EmbedStatus = EmbedStatus.Idle,
    onEmbedStatusShown: () -> Unit = {},
) {
    // Which of the menu's three surfaces is open. `rememberSaveable` so a rotation with the history open
    // comes back to the history rather than to the screen behind it.
    var openSurface by rememberSaveable { mutableStateOf(BookSurface.None) }
    val snackbars = remember { SnackbarHostState() }
    val text = message.asText()
    // Keyed by the message, so two different results in a row show two snackbars rather than one.
    LaunchedEffect(text) {
        snackbars.showSnackbar(text ?: return@LaunchedEffect)
        onMessageShown()
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbars) },
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
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // PRODUCT_SPEC MGR-007 — above the book rather than over it. The embed runs for minutes, and the
            // user is free to keep reading the screen or to leave and come back; a dialog would trap them and
            // a snackbar would vanish long before the server had finished.
            EmbedStatusBanner(status = embed, onDismiss = onEmbedStatusShown)
            val content = Modifier.fillMaxSize()
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
                    actions = actions,
                    // Which surface opens is this screen's own business, so those three callbacks are assembled
                    // here rather than being three more parameters the caller has to know about.
                    menuActions = BookMenuActions(
                        onOpenHistory = { openSurface = BookSurface.History },
                        onFinishedChanged = actions.onFinishedChanged,
                        onDiscardRequested = { openSurface = BookSurface.DiscardConfirmation },
                        onOpenWebClient = actions.onOpenWebClient,
                        onOpenInfo = { openSurface = BookSurface.Info },
                        webUrl = menu.webUrl,
                        isDownloaded = menu.download is DownloadButtonState.Downloaded,
                        onRemoveDownload = { openSurface = BookSurface.RemoveDownloadConfirmation },
                        onManageDownloads = actions.onManageDownloads,
                        onEditMetadata = { actions.onEditMetadata(uiState.book.id) },
                        onRemoveFromServer = { openSurface = BookSurface.RemoveFromServerConfirmation },
                        canRemoveFromServer = menu.canRemoveFromServer,
                        onEmbedMetadata = { openSurface = BookSurface.EmbedConfirmation },
                        canEmbedMetadata = menu.canEmbedMetadata,
                    ),
                    download = DownloadControl(
                        isPermitted = menu.canDownload,
                        state = menu.download,
                        onClick = {
                            // The one state that asks first: removing is the only tap here that deletes files.
                            if (menu.download is DownloadButtonState.Downloaded) {
                                openSurface = BookSurface.RemoveDownloadConfirmation
                            } else {
                                actions.onDownloadClicked(menu.download)
                            }
                        },
                    ),
                    modifier = content,
                )
            }
        }
    }

    val book = (uiState as? BookUiState.Loaded)?.book
    if (book != null) {
        BookSurfaces(
            book = book,
            menu = menu,
            actions = actions,
            open = openSurface,
            onClose = { openSurface = BookSurface.None },
        )
    }
}

/**
 * Whichever sheet or dialog the menu opened, and nothing when it opened none.
 *
 * Extracted from [BookScreen] because that composable reached detekt's complexity limit when the embed
 * confirmation arrived, and the limit was right: a screen whose body is a scaffold *and* a seven-way
 * dispatch is two things. It is also a better shape — a `when` over the enum is exhaustive, so a surface
 * added to [BookSurface] without a branch here is a compile error rather than a dialog that never opens.
 */
@Composable
private fun BookSurfaces(
    book: Book,
    menu: BookMenuState,
    actions: BookActions,
    open: BookSurface,
    onClose: () -> Unit,
) {
    when (open) {
        BookSurface.None -> Unit

        BookSurface.History -> HistorySheet(
            entries = menu.history,
            chapters = menu.chapters,
            // Read-only here, unlike the player's copy of this sheet. The player is *at* a position and can
            // return to one; this screen may be showing a book that is not playing, and a row that started
            // playback from a tap meant for a record would move a listener without being asked. Playing from
            // a history entry is worth having and is worth its own decision, not a side effect of this menu.
            onReturnTo = {},
            onDismiss = onClose,
        )

        BookSurface.Info -> BookInfoSheet(book = book, onDismiss = onClose)

        BookSurface.DiscardConfirmation -> DiscardProgressDialog(
            onConfirm = {
                onClose()
                actions.onDiscardProgress()
            },
            onDismiss = onClose,
        )

        BookSurface.RemoveDownloadConfirmation -> RemoveDownloadDialog(
            onConfirm = {
                onClose()
                actions.onRemoveDownload()
            },
            onDismiss = onClose,
        )

        BookSurface.EmbedConfirmation -> EmbedMetadataDialog(
            title = book.title,
            onConfirm = {
                onClose()
                actions.onEmbedMetadata()
            },
            onDismiss = onClose,
        )

        BookSurface.RemoveFromServerConfirmation -> RemoveFromServerDialog(
            title = book.title,
            isDownloaded = menu.download is DownloadButtonState.Downloaded,
            onConfirm = { alsoRemoveDownload ->
                onClose()
                actions.onRemoveFromServer(alsoRemoveDownload)
            },
            onDismiss = onClose,
        )
    }
}

/**
 * PRODUCT_SPEC MGR-005 — the confirmation, which has to describe the actual effect.
 *
 * Three clauses, and each one is there because the label alone would be read as something worse:
 *
 * - **it leaves the database** — the thing that was asked for;
 * - **the media files stay on the server** — the fear. CLAUDE.md forbids this app from ever claiming
 *   otherwise, and this is the sentence that keeps the promise. It is true because of what the request
 *   does *not* carry: `?hard=1` is the flag that would delete the files, and ADR-0021 records why this app
 *   never sends it;
 * - **a later scan may add it back** — because it will, and a user who did not expect that would think the
 *   removal had failed.
 *
 * The download checkbox is separate and starts unchecked, exactly as the requirement specifies: they are
 * two different copies, and somebody removing a duplicate from their library has not asked to lose the
 * hours they downloaded over hotel wi-fi.
 */
@Composable
private fun RemoveFromServerDialog(
    title: String,
    isDownloaded: Boolean,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var alsoRemoveDownload by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.book_remove_server_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.book_remove_server_body, title))
                if (isDownloaded) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = alsoRemoveDownload,
                            onCheckedChange = { checked -> alsoRemoveDownload = checked },
                        )
                        Text(stringResource(R.string.book_remove_server_downloads))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(alsoRemoveDownload) }) {
                Text(stringResource(R.string.book_remove_server_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.book_remove_server_cancel)) }
        },
    )
}

/**
 * PRODUCT_SPEC MGR-005 — the sentence for a result, resolved where the resources are.
 *
 * A successful removal needs a *localised* line, and the `ViewModel` has no business holding one; a failure
 * already carries its own words from the domain. Lifting the `when` out of `BookScreen` also keeps that
 * composable under detekt's complexity limit, which it crossed the moment this became two cases.
 */
@Composable
private fun BookMessage?.asText(): String? = when (this) {
    null -> null
    is BookMessage.Failed -> summary
    BookMessage.RemovedFromServer -> stringResource(R.string.book_remove_server_done)
}

/**
 * PRODUCT_SPEC DL-001 — the download control, as one argument.
 *
 * Three values that are only ever used together, bundled for the reason detekt's parameter limit exists:
 * `BookHeader` reached nine when the control arrived, and a composable with nine has an argument order
 * somebody will eventually get wrong. It is also the honest grouping — whether the control exists, what it
 * shows, and what a tap does are one decision.
 *
 * @property isPermitted DL-001 criterion 1. `false` hides the control entirely rather than disabling it.
 */
@Immutable
internal data class DownloadControl(val isPermitted: Boolean, val state: DownloadButtonState, val onClick: () -> Unit)

/** Which of the screen's dialogs or sheets is showing. Saveable, so it survives a rotation. */
private enum class BookSurface {
    None,
    History,
    Info,
    DiscardConfirmation,
    RemoveDownloadConfirmation,

    /** PRODUCT_SPEC MGR-005 — the only surface on this screen that changes somebody else's server. */
    RemoveFromServerConfirmation,

    /** PRODUCT_SPEC MGR-007 — the only surface that changes files on it. */
    EmbedConfirmation,
}

/**
 * What this screen can do that only its callers can perform.
 *
 * A bundle rather than five parameters, for the reason detekt's limit exists: `BookScreen` reached ten
 * parameters when the menu arrived, and a composable with ten has an argument order somebody will get wrong.
 * The menu's own three callbacks are *not* here — which surface opens is the screen's business, and putting
 * them in this type would make every caller supply state it does not own.
 */
@Immutable
data class BookActions(
    val onPlay: (LibraryItemId) -> Unit,
    val onTogglePlayPause: () -> Unit,
    val onFinishedChanged: (Boolean) -> Unit,
    val onDiscardProgress: () -> Unit,
    val onOpenWebClient: (String) -> Unit,
    /** PRODUCT_SPEC DL-001 — a tap on the download control, carrying the state it was showing. */
    val onDownloadClicked: (DownloadButtonState) -> Unit = {},
    /** The confirmed half of *remove*, which is the only tap on this screen that deletes files. */
    val onRemoveDownload: () -> Unit = {},
    /** PRODUCT_SPEC DL-003 — opens the list of everything downloaded on this device. */
    val onManageDownloads: () -> Unit = {},
    /** PRODUCT_SPEC MGR-001 — opens the metadata editor for this book. */
    val onEditMetadata: (LibraryItemId) -> Unit = {},
    /** PRODUCT_SPEC MGR-005 — the confirmed removal, carrying the separate download checkbox's answer. */
    val onRemoveFromServer: (Boolean) -> Unit = {},
    /** PRODUCT_SPEC MGR-007 — after the confirmation. Takes no arguments: there is nothing to choose. */
    val onEmbedMetadata: () -> Unit = {},
)

@Composable
private fun BookDetails(
    book: Book,
    playback: PlaybackUiState,
    actions: BookActions,
    menuActions: BookMenuActions,
    download: DownloadControl,
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
            onPlay = actions.onPlay,
            onTogglePlayPause = actions.onTogglePlayPause,
            actions = menuActions,
            download = download,
        )

        // Length, tracks and availability as one quiet strip, not three sentences. Facts of the same
        // kind belong on the same line, and a reader scans a strip faster than they read a list.
        FactStrip(book = book)

        ProgressSummary(book = book, modifier = Modifier.padding(horizontal = 16.dp))

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
 * Download appears only for an account the server lets download (PRODUCT_SPEC DL-001), and where it does
 * appear it is a placeholder that says so: disabled, with a content description naming the phase it arrives
 * in rather than implying a button that silently does nothing. A control that looks live and is not is worse
 * than one that admits it (PRODUCT_SPEC 21).
 */
@Composable
private fun BookHeader(
    book: Book,
    playback: PlaybackUiState,
    onPlay: (LibraryItemId) -> Unit,
    onTogglePlayPause: () -> Unit,
    actions: BookMenuActions,
    download: DownloadControl,
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
                // PRODUCT_SPEC DL-001 criterion 1 — "visible only when the server grants download
                // permission". Absent rather than disabled for an account without the grant: a greyed
                // control is a promise that pressing it might one day work, and for this account it will
                // not, whatever this app ships.
                if (download.isPermitted) {
                    DownloadButton(state = download.state, onClick = download.onClick)
                }
                PlayIconButton(
                    book = book,
                    playback = playback,
                    onPlay = onPlay,
                    onTogglePlayPause = onTogglePlayPause,
                )
                BookOverflowMenu(book = book, actions = actions)
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
private fun ProgressSummary(book: Book, modifier: Modifier = Modifier) {
    val progress = book.progress ?: return
    val isFinished = progress.isFinished
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val percent = (progress.fractionComplete * PERCENT).roundToInt()
        Text(
            text = if (isFinished) {
                stringResource(R.string.book_finished)
            } else {
                stringResource(R.string.book_progress, percent)
            },
            style = MaterialTheme.typography.labelLarge,
        )
        LinearProgressIndicator(progress = { progress.fractionComplete }, modifier = Modifier.fillMaxWidth())
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
