package com.example.shelfplayer.feature.book

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ManagementAction
import com.example.shelfplayer.core.model.ManagementBlock
import com.example.shelfplayer.core.model.ManagementPermissions
import com.example.shelfplayer.core.model.download.DownloadState
import com.example.shelfplayer.core.model.download.OfflineBook
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.playback.PlaybackHistoryEntry
import com.example.shelfplayer.core.model.realtime.RealtimeStatus
import com.example.shelfplayer.domain.repository.DownloadRepository
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.usecase.DownloadBookUseCase
import com.example.shelfplayer.domain.usecase.EmbedTaskState
import com.example.shelfplayer.domain.usecase.ObserveBookChaptersUseCase
import com.example.shelfplayer.domain.usecase.ObserveBookDetailsUseCase
import com.example.shelfplayer.navigation.ShelfDestinations
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration

/** PRODUCT_SPEC LIB-004 — the book detail screen, read entirely from cached state. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BookViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observeBookDetails: ObserveBookDetailsUseCase,
    observeChapters: ObserveBookChaptersUseCase,
    private val history: PlaybackHistoryRepository,
    private val profiles: ProfileRepository,
    private val downloads: DownloadRepository,
    private val downloadBook: DownloadBookUseCase,
    private val server: BookServerActions,
    private val playbackRepository: PlaybackRepository,
) : ViewModel() {

    private val bookId: LibraryItemId = LibraryItemId(
        requireNotNull(savedStateHandle.get<String>(ShelfDestinations.ARG_BOOK_ID)) {
            "Book route is missing its ${ShelfDestinations.ARG_BOOK_ID} argument"
        },
    )

    /**
     * PRODUCT_SPEC PLAY-003 — pulls the **server's** own session records in, when the pane is opened.
     *
     * This screen is where it matters most. The derived remote history could only ever describe a book this
     * device had already played — `LibrarySnapshotWriter.recordRemoteChange` has nothing to diff against
     * otherwise — and this screen is regularly showing a book the listener started somewhere else. The rows
     * are persisted, so the pane fills from Room and survives losing the network; failure is silent inside
     * the repository, because a pane whose local half is good must not become an error.
     */
    fun onOpenHistory() {
        viewModelScope.launch { history.refreshServerSessions(bookId) }
    }

    val uiState: StateFlow<BookUiState> = observeBookDetails(bookId)
        .map { book ->
            if (book == null) BookUiState.Missing else BookUiState.Loaded(book)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = BookUiState.Loading,
        )

    /**
     * PRODUCT_SPEC PLAY-003 / 11.1 — what the overflow menu's sheets need beyond the book itself.
     *
     * One flow rather than three, for the reason `SettingsViewModel` gives: three flows into the same screen
     * are three chances for it to hold one value from before a change and two from after. The history and the
     * chapters travel together because the history sheet renders them together — a row's chapter label is what
     * turns "at 4:12:30" into a memory.
     */
    val menu: StateFlow<BookMenuState> = combine(
        history.observe(bookId),
        observeChapters(bookId),
        profiles.observeServers(),
        // Kotlin's typed `combine` stops at five flows, and the sixth is the manifest. Pairing the profile
        // with it is not a workaround for the arity — the two are read together everywhere below, because
        // both the download grant and the download itself belong to the same account.
        profiles.observeActiveProfile().flatMapLatest { profile ->
            val manifest = if (profile == null) flowOf(null) else downloads.observe(profile.serverId, bookId)
            // Connectivity joins the pair rather than becoming a sixth source — `combine`'s typed overloads
            // stop at five, and MGR-005 needs the grant and the connection together anyway: either one
            // missing means the same thing to the menu.
            manifest.combine(server.network.isOnline) { offline, isOnline -> Account(profile, offline, isOnline) }
        },
        uiState,
    ) { entries, chapters, servers, account, state ->
        val (profile, offline, isOnline) = account
        val permissions = if (profile == null) {
            null
        } else {
            ManagementPermissions(
                profileRole = profile.role,
                canUpdate = profile.canUpdate,
                canDelete = profile.canDelete,
                canUpload = profile.canUpload,
                // The server capability set is not read here: MGR-005 needs no capability, only the grant
                // and a connection, and `ManagementPermissions` asks for neither on this action.
                capabilities = null,
                isOnline = isOnline,
            )
        }
        // `if` rather than `permissions?.blockOn(…) ?: …`: `blockOn` returns `null` for *available*, so an
        // elvis folds the good answer into the fallback and reports every permitted account as blocked. That
        // was a real bug here once — the compiler caught it as an always-false condition, and it would
        // otherwise have been a silently missing menu row.
        val removalBlock = if (permissions == null) {
            ManagementBlock.Permission
        } else {
            permissions.blockOn(ManagementAction.RemoveFromDatabase)
        }
        // PRODUCT_SPEC MGR-007 — the same permissions object, a different action. Admin or root only, which
        // is the account *type* rather than any grant: the server's route gates on `isAdminOrUp`.
        val embedBlock = if (permissions == null) {
            ManagementBlock.Permission
        } else {
            permissions.blockOn(ManagementAction.EmbedMetadata)
        }
        val book = (state as? BookUiState.Loaded)?.book
        BookMenuState(
            history = entries,
            chapters = chapters,
            // PRODUCT_SPEC DL-001 — the server's grant, not a guess. `false` while no profile is loaded,
            // which is the same safe direction the column defaults to.
            canDownload = profile?.canDownload == true,
            // PRODUCT_SPEC MGR-005 — the grant *and* connectivity. "Offline invocation is blocked" is a
            // criterion, and gating on the grant alone let somebody confirm a destructive dialogue on a
            // train and receive a generic network error for it. Absent rather than greyed, for the same
            // reason the download control is: a disabled destructive row invites a tap that will fail.
            canRemoveFromServer = removalBlock == null,
            // PRODUCT_SPEC MGR-007 — absent rather than greyed for a non-administrator, like every other
            // action on this menu whose permission will never arrive by waiting.
            canEmbedMetadata = embedBlock == null,
            download = downloadStateOf(offline),
            // ADR note in `BookOverflowMenu`: the web client's own route, not an API endpoint.
            webUrl = book?.let { loaded ->
                servers.firstOrNull { it.id == loaded.serverId }
                    ?.baseUrl
                    ?.trimEnd('/')
                    ?.let { base -> "$base/item/${loaded.id.value}" }
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = BookMenuState(),
    )

    private val _embed = MutableStateFlow<EmbedStatus>(EmbedStatus.Idle)

    /** PRODUCT_SPEC MGR-007 — "the operation is non-blocking and has visible status". This is the status. */
    val embed: StateFlow<EmbedStatus> = _embed.asStateFlow()

    /** So a second request replaces the first watcher rather than leaving two collectors on the socket. */
    private var embedJob: Job? = null

    private val _message = MutableStateFlow<BookMessage?>(null)

    /** A one-line result for an action that reached the network, or `null`. Cleared when acknowledged. */
    val message: StateFlow<BookMessage?> = _message.asStateFlow()

    fun onMessageShown() {
        _message.value = null
    }

    /**
     * PRODUCT_SPEC PLAY-004 — "marking finished is explicit", including un-marking it.
     *
     * The position comes from the row rather than from the caller, so un-marking leaves the listener where
     * they were. Marking finished ignores it — the repository moves the position to the end of the book,
     * which is the only position a finished book can honestly report.
     */
    /**
     * PRODUCT_SPEC MGR-005 — remove the item from the server's database, after the screen confirmed it.
     *
     * A message either way. This is the one action on this screen whose effect the user cannot see by
     * looking — the book leaves the list, and "did that work" has to be answerable without checking the
     * server.
     */
    fun onRemoveFromServer(alsoRemoveDownload: Boolean) {
        viewModelScope.launch {
            _message.value = when (val removed = server.removeFromServer(bookId, alsoRemoveDownload)) {
                is AppResult.Failure -> BookMessage.Failed(removed.error.summary)
                // Said out loud. This is the one action on this screen whose effect the user cannot see by
                // looking — the book leaves the shelf either way — so "did that work" has to be answerable
                // without opening the server.
                is AppResult.Success -> BookMessage.RemovedFromServer
            }
        }
    }

    /**
     * PRODUCT_SPEC MGR-007 — asks the server to write this item's metadata into its own audio files.
     *
     * ### Why this has a state machine and the other actions have a message
     *
     * Because every other action on this screen finishes before the call returns. This one returns as soon
     * as the server has *queued* the work, and the work then happens for as long as the book is long. A
     * one-line "done" would be a lie at the only moment it could be shown.
     *
     * So the request sets [EmbedStatus.Running] and a collector waits for the server's own verdict on the
     * websocket. [onEmbedStatusShown] is what clears it, because a terminal state is something the user has
     * to read rather than something to time out.
     *
     * ### Why a dropped connection is not a failure
     *
     * Nothing replays a missed `task_finished`. If the socket goes down while the task is running, this app
     * cannot know how it ended — so it says exactly that ([EmbedStatus.Unknown]) rather than picking the
     * optimistic answer. MGR-007's *"a failed operation never marks local metadata as embedded"* is a
     * requirement about not claiming success, and "the connection dropped" is the case where claiming it
     * would be easiest and least justified.
     */
    fun onEmbedMetadata() {
        // Guarded, not queued. A second tap while one is running would send a second request the server
        // answers with "already in queue" — harmless, and still a request nobody meant to make.
        if (_embed.value is EmbedStatus.Running || _embed.value is EmbedStatus.Requesting) return

        embedJob?.cancel()
        embedJob = viewModelScope.launch {
            _embed.value = EmbedStatus.Requesting
            when (val asked = server.embedMetadata(bookId)) {
                is AppResult.Failure -> _embed.value = EmbedStatus.Failed(asked.error.summary)
                is AppResult.Success -> {
                    // Both outcomes are "the server is working on it". `AlreadyRunning` is not an error:
                    // somebody — possibly this user on another device — already started it, and the honest
                    // report is the same status with the same ending.
                    _embed.value = EmbedStatus.Running
                    watchEmbed()
                }
            }
        }
    }

    /** Clears a terminal embed status once the user has read it. */
    fun onEmbedStatusShown() {
        if (_embed.value is EmbedStatus.Running || _embed.value is EmbedStatus.Requesting) return
        _embed.value = EmbedStatus.Idle
    }

    /**
     * Waits for the server's verdict, or for the connection that would carry it to go away.
     *
     * One `first` on a merged flow rather than two racing collectors, because the two events are the same
     * question — *what is the last thing this device can honestly say?* — and merging them means neither has
     * to cancel the other. Whichever arrives first is the answer.
     */
    private suspend fun watchEmbed() {
        val profileId = profiles.activeProfileId() ?: return

        val verdicts = server.embedTasks.outcomes(profileId, bookId).map { state ->
            when (state) {
                is EmbedTaskState.Failed -> EmbedStatus.ServerFailed(state.hasServerError)
                EmbedTaskState.Finished -> EmbedStatus.Finished
                // `task_started`, which the request already reported. Filtered out by the `first` below
                // rather than dropped here, so that the mapping stays a total function.
                EmbedTaskState.Running -> EmbedStatus.Running
            }
        }
        // `Disconnected` specifically, and not `Connecting` or `Idle`: the first is a live connection that
        // dropped — so a `task_finished` may already have been missed — and the other two are states the
        // socket passes through on its way up, before there was anything to miss.
        val drops = server.embedTasks.connection
            .filter { status -> status == RealtimeStatus.Disconnected }
            .map { EmbedStatus.Unknown }

        val terminal = merge(verdicts, drops).first { status -> status != EmbedStatus.Running }
        // Only if nothing else has moved on. A user who dismissed the notice, or started something else,
        // should not have a late verdict reappear over the top of it.
        if (_embed.value is EmbedStatus.Running) _embed.value = terminal
    }

    fun onFinishedChanged(isFinished: Boolean) {
        viewModelScope.launch {
            val at = currentBook()?.progress?.position ?: Duration.ZERO
            report(playbackRepository.setFinished(bookId, isFinished, at))
        }
    }

    /**
     * PRODUCT_SPEC PLAY-004 / 21 — sends the book back to the beginning, deliberately.
     *
     * The one destructive action on this screen, and it is destructive in a specific and limited way: it
     * discards **where you had got to**, on this device and on the server, and nothing else. No audio file is
     * touched, no download is removed, and the book stays in the library. The confirmation says exactly that,
     * because "discard progress" on its own could mean any of them.
     *
     * Implemented as an un-finish to position zero — the same progress `PATCH` the *Finished* control already
     * uses, whose acceptance is a captured contract (`contracts/media-progress-set-unfinished.json`). There is
     * no separate discard route in the captures, and inventing one would be guessing at the server
     * (PRODUCT_SPEC 22.4).
     */
    fun onDiscardProgress() {
        viewModelScope.launch {
            report(playbackRepository.setFinished(bookId, isFinished = false, position = Duration.ZERO))
        }
    }

    /**
     * PRODUCT_SPEC DL-001 — the one control, and what a tap means in each state.
     *
     * The ordering is the *user's* mental model rather than the manifest's: a book that is here is here, even
     * if the last thing recorded on it was a failure, so `Complete` wins over `Failed`. The alternative would
     * offer *retry* on a book that is already playable offline.
     */
    private fun downloadStateOf(offline: OfflineBook?): DownloadButtonState = when {
        offline == null -> DownloadButtonState.NotDownloaded
        offline.isComplete -> DownloadButtonState.Downloaded
        offline.state == DownloadState.Failed -> DownloadButtonState.Failed
        else -> DownloadButtonState.Downloading(progress = offline.fractionOrNull())
    }

    /**
     * The fraction downloaded, or `null` before the first byte.
     *
     * `null` shows an indeterminate ring. A determinate one frozen at zero looks exactly like a download that
     * never started, and that is precisely the moment a user is deciding whether the button worked.
     */
    private fun OfflineBook.fractionOrNull(): Float? {
        val total = totalBytes
        if (total <= 0 || downloadedBytes <= 0) return null
        return (downloadedBytes.toFloat() / total).coerceIn(0f, 1f)
    }

    /**
     * PRODUCT_SPEC DL-001 — the tap, dispatched by the state the button was showing.
     *
     * Cancelling and removing are deliberately different: cancel leaves the partial files, because they are
     * what a retry resumes from and a user who stopped a download on a train has not asked to throw away what
     * they already have. Removing is the destructive one and the screen confirms it first.
     */
    fun onDownloadClicked(state: DownloadButtonState) {
        viewModelScope.launch {
            when (state) {
                is DownloadButtonState.NotDownloaded, is DownloadButtonState.Failed -> report(downloadBook(bookId))
                is DownloadButtonState.Downloading -> report(server.removeDownload.cancel(bookId))
                is DownloadButtonState.Downloaded -> Unit
            }
        }
    }

    /** PRODUCT_SPEC 21 — the confirmed half of *remove*, which is the only action here that deletes files. */
    fun onRemoveDownload() {
        viewModelScope.launch { report(server.removeDownload(bookId)) }
    }

    private suspend fun currentBook(): Book? = (uiState.first() as? BookUiState.Loaded)?.book

    /**
     * Surfaces a failure and stays quiet about success.
     *
     * Both writes here are local-first: the row is already written by the time the network is attempted, so a
     * failure is "the server has not heard yet" rather than "nothing happened". Saying so is the honest
     * message; a success banner for something the screen already shows would be noise.
     */
    private fun report(result: com.example.shelfplayer.core.model.AppResult<Unit>) {
        if (result is com.example.shelfplayer.core.model.AppResult.Failure) {
            _message.value = BookMessage.Failed(result.error.summary)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * PRODUCT_SPEC LIB-004 / PLAY-003 — what the overflow menu can show, beyond the book.
 *
 * @property webUrl this item in the server's own web client, or `null` when the server's address is not
 *   known — which is the state of a profile that has been signed out.
 */
/**
 * PRODUCT_SPEC MGR-005 — what to tell the user after an action that reached the network.
 *
 * A type rather than a `String?` because one of the two cases has no string to carry: a successful removal
 * needs a *localised* sentence, and a `ViewModel` that produced one would be holding a resource. The screen
 * resolves it; this says only what happened.
 */
sealed interface BookMessage {
    /** Something went wrong, described by the domain in its own words. */
    data class Failed(val summary: String) : BookMessage

    /** PRODUCT_SPEC MGR-005 — the removal landed. Said out loud, because its effect is invisible. */
    data object RemovedFromServer : BookMessage
}

/**
 * PRODUCT_SPEC MGR-007 — where an embed has got to, as far as this device can honestly tell.
 *
 * Six states, and the sixth is the one that matters. [Unknown] is not a failure and not a success: the
 * request was accepted, the connection that would have carried the verdict went down, and nothing replays
 * it. Reporting that as *done* is the specific mistake MGR-007's last criterion is written against, and
 * reporting it as *failed* would send somebody looking for a problem that may not exist.
 */
sealed interface EmbedStatus {
    data object Idle : EmbedStatus

    /** The request is in flight. Nothing has been queued yet, so nothing has been written. */
    data object Requesting : EmbedStatus

    /** The server has the job. This is where minutes are spent on a long book. */
    data object Running : EmbedStatus

    /** `task_finished`, with no failure. The audio files on the server have been rewritten. */
    data object Finished : EmbedStatus

    /** The *request* was refused — no permission, no connection, not a book with audio files. */
    data class Failed(val summary: String) : EmbedStatus

    /**
     * The *task* failed on the server.
     *
     * @property hasServerError whether the server attached a reason. The reason itself is deliberately not
     *   carried: it can quote a path inside somebody's library (PRODUCT_SPEC 14.5), and the actionable half
     *   is "the server knows why — look there".
     */
    data class ServerFailed(val hasServerError: Boolean) : EmbedStatus

    /** The connection dropped while the task was running. The outcome cannot be known from here. */
    data object Unknown : EmbedStatus
}

/** The active profile, its download manifest and whether the device can reach the server. */
private data class Account(
    val profile: com.example.shelfplayer.core.model.Profile?,
    val offline: OfflineBook?,
    val isOnline: Boolean,
)

data class BookMenuState(
    val history: List<PlaybackHistoryEntry> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    /**
     * PRODUCT_SPEC DL-001 — whether this account may download from its server.
     *
     * Decides whether the control exists at all — DL-001 criterion 1, "visible only when the server grants
     * download permission". Absent rather than greyed for an account without it: a disabled button is a
     * promise that pressing it might one day work, and for that account it will not.
     */
    val canDownload: Boolean = false,
    /** PRODUCT_SPEC DL-001 — what the download control shows, and therefore what a tap does. */
    val download: DownloadButtonState = DownloadButtonState.NotDownloaded,
    val webUrl: String? = null,
    /** PRODUCT_SPEC MGR-005 — whether this account may remove the item from the server's database. */
    val canRemoveFromServer: Boolean = false,
    /**
     * PRODUCT_SPEC MGR-007 — whether this account may ask the server to rewrite the item's audio files.
     *
     * Administrators only, and the server decides that by account type rather than by grant — so an account
     * holding update, delete and upload still does not get this row.
     */
    val canEmbedMetadata: Boolean = false,
)

/**
 * PRODUCT_SPEC 21 — loading and "not there" are different states.
 *
 * A screen that renders "book not available" while the query is still running is the reason this is
 * a sealed hierarchy rather than a nullable field.
 */
sealed interface BookUiState {
    data object Loading : BookUiState

    data object Missing : BookUiState

    data class Loaded(val book: Book) : BookUiState
}
