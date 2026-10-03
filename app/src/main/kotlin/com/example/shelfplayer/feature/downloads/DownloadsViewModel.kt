package com.example.shelfplayer.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.DownloadProgress
import com.example.shelfplayer.core.model.download.DownloadStorageState
import com.example.shelfplayer.core.model.download.OfflineBook
import com.example.shelfplayer.core.model.download.StorageVolumeOption
import com.example.shelfplayer.core.model.download.VerificationReport
import com.example.shelfplayer.core.model.download.durableDownloadProgress
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.domain.download.DownloadExecutionKey
import com.example.shelfplayer.domain.download.DownloadExecutionObserver
import com.example.shelfplayer.domain.download.DownloadExecutionSnapshot
import com.example.shelfplayer.domain.download.DownloadLocations
import com.example.shelfplayer.domain.download.DownloadRecoveryAction
import com.example.shelfplayer.domain.download.DownloadRecoveryPolicy
import com.example.shelfplayer.domain.download.DownloadRecoveryState
import com.example.shelfplayer.domain.download.OfflineFiles
import com.example.shelfplayer.domain.download.OfflineVerification
import com.example.shelfplayer.domain.download.recoveryAction
import com.example.shelfplayer.domain.repository.DownloadRepository
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.usecase.DownloadRemoval
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PRODUCT_SPEC DL-003 / SET-002 / ADR-0018 decisions 6 and 8 — *Manage local files*.
 *
 * ### Every download on the device, not this profile's
 *
 * The owner's decision 6: *"a simple solution can show all downloaded books for all users in the setting.
 * So if I go into a user that doesn't have that book, I can still see all downloaded books in the
 * settings."* This screen answers *what is using space on this phone*, which is a fact about the device.
 *
 * PRODUCT_SPEC 5.2 is honoured at the **title**, not at the row. A book the current profile cannot see is
 * listed with its size and without its name so device usage remains truthful. PD-003 still keeps Remove
 * profile-scoped: a row the active profile does not claim is informational and exposes no destructive action.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloads: DownloadRepository,
    private val files: OfflineFiles,
    private val verification: OfflineVerification,
    private val profiles: ProfileRepository,
    private val locations: DownloadLocations,
    private val execution: DownloadExecutionObserver,
    /** Pause, resume/retry and claim-aware remove: the row's three actions. */
    private val actions: DownloadRowActions,
    library: LibraryRepository,
) : ViewModel() {

    /**
     * PRODUCT_SPEC DL-003 / ADR-0020 — the volumes downloads can go to, and which one is chosen.
     *
     * Outside [uiState] because the list is a *device* fact read once per visit rather than a flow: a card
     * appearing while the screen is open is rare enough that re-reading on open is the honest cost, and
     * folding it in would push the `combine` past its typed arity for a list that does not change.
     */
    private val _volumes = MutableStateFlow<List<StorageVolumeOption>>(emptyList())
    val volumes: StateFlow<List<StorageVolumeOption>> = _volumes.asStateFlow()

    val selectedVolume: StateFlow<String> = locations.observeSelected().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = StorageVolumeOption.INTERNAL_UUID,
    )

    private val availableVolumeUuids = locations.observeAvailableVolumeUuids().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = setOf(StorageVolumeOption.INTERNAL_UUID),
    )

    val selectedVolumeUnavailable: StateFlow<Boolean> = combine(
        selectedVolume,
        availableVolumeUuids,
    ) { selected, available ->
        selected.isNotEmpty() && selected !in available
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = false,
    )

    init {
        viewModelScope.launch {
            availableVolumeUuids.collect {
                _volumes.value = locations.options()
            }
        }
    }

    /**
     * PRODUCT_SPEC DL-003 — chooses where the *next* download goes.
     *
     * Nothing moves. Every downloaded file's location is recorded absolutely in the manifest, so the books
     * already on the device keep playing from where they are; this is not a migration and does not pretend
     * to be one. The hint under the picker says so, because "change download location" reads like a promise
     * to move things.
     */
    fun onVolumeChosen(uuid: String) {
        viewModelScope.launch {
            when (val outcome = locations.select(uuid)) {
                is AppResult.Failure -> _message.value = outcome.error.summary
                is AppResult.Success -> _volumes.value = locations.options()
            }
        }
    }

    private val visibleBooks = profiles.observeActiveProfile().flatMapLatest { profile ->
        if (profile == null) {
            flowOf(VisibleBooks(profileId = null, books = emptyList()))
        } else {
            library.observeAccessibleBooks(profile.id).map { books -> VisibleBooks(profile.id, books) }
        }
    }

    /**
     * Durable physical-copy truth is shared once; transient execution observation is derived from its keys.
     * WorkManager is deliberately not copied into Room merely so this screen can render it.
     */
    private val storedDownloads = downloads.observeAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = emptyList(),
    )

    private val executionEvidence = storedDownloads
        .map { stored -> stored.mapTo(linkedSetOf()) { DownloadExecutionKey(it.serverId, it.itemId) } }
        .distinctUntilChanged()
        .flatMapLatest(execution::observe)

    /**
     * #22 filesystem projection. It is intentionally transient: reclaimable .part bytes belong to disk,
     * not Room. A fresh projection is rebuilt whenever a durable row changes, including pause/failure,
     * discard bookkeeping updates and process reconstruction.
     */
    private val partialBytes = storedDownloads.mapLatest { stored ->
        buildMap {
            stored
                .filter { book ->
                    book.state == com.example.shelfplayer.core.model.download.DownloadState.Paused ||
                        book.state == com.example.shelfplayer.core.model.download.DownloadState.Failed
                }
                .forEach { book ->
                    when (val result = files.partialBytes(book.serverId, book.itemId)) {
                        is AppResult.Success -> put(DownloadExecutionKey(book.serverId, book.itemId), result.value)
                        is AppResult.Failure -> Unit
                    }
                }
        }
    }

    private val transientPresentation = combine(
        executionEvidence,
        partialBytes,
        availableVolumeUuids,
    ) { executions, partials, available ->
        DownloadTransientPresentation(
            executions = executions,
            partialBytes = partials,
            availableVolumeUuids = available,
        )
    }

    val uiState: StateFlow<DownloadsUiState> = combine(
        storedDownloads,
        downloads.observeTotalBytes(),
        visibleBooks,
        transientPresentation,
    ) { stored, totalBytes, visible, transient ->
        // DL-003 / PRODUCT_SPEC 5.2: item IDs are server-local. A visible book on another server is not
        // permission to label this physical copy or expose its failure details.
        val byKey = visible.books.associateBy { DownloadExecutionKey(it.serverId, it.id) }
        DownloadsUiState(
            books = stored.map { copy ->
                copy.toRow(
                    book = byKey[DownloadExecutionKey(copy.serverId, copy.itemId)],
                    activeProfileId = visible.profileId,
                    execution = transient.executions[DownloadExecutionKey(copy.serverId, copy.itemId)],
                    partialBytes = transient.partialBytes[DownloadExecutionKey(copy.serverId, copy.itemId)] ?: 0L,
                    storageState = storageState(copy.storageVolumeUuid, transient.availableVolumeUuids),
                )
            },
            totalBytes = totalBytes,
            isLoaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = DownloadsUiState(),
    )

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun onMessageShown() {
        _message.value = null
    }

    /**
     * PRODUCT_SPEC DL-003 — releases this profile's claim, and the files if it was the last.
     *
     * Goes through [RemoveDownloadUseCase] rather than the file store: removing a copy nobody else claims
     * must also cancel its in-flight transfer, or the worker keeps writing into files that were just
     * deleted. The use case acts on the *active* profile's server, so a row from another server is refused
     * here rather than resolved against the wrong item.
     */
    fun onRemove(bookId: LibraryItemId, serverId: ServerId) {
        viewModelScope.launch {
            val profile = profiles.observeActiveProfile().first() ?: return@launch
            if (profile.serverId != serverId) return@launch
            when (val removed = actions.remove(bookId)) {
                is AppResult.Failure -> _message.value = removed.error.summary

                is AppResult.Success -> if (removed.value == DownloadRemoval.ClaimReleased) {
                    // The honest report. Somebody else on this device still wants the book, so nothing was
                    // freed — and a silent success would leave the user wondering why the number did not
                    // move (DL-003 criterion 5).
                    _message.value = SHARED_COPY_KEPT
                }
            }
        }
    }

    /**
     * PRODUCT_SPEC DL-002 / ADR-0018 decision 8 — *Repair*, which is a check rather than a fix.
     *
     * The owner asked for *"a repair button [that] will check the sha of each book against the server
     * version, then prompt to delete and redownload."* The capture says the server sends an **`ETag`**, not
     * a checksum — a validator whose only guaranteed property is that it changes when the file changes —
     * so what this can honestly do is verify the files are present, whole and openable, and mark the ones
     * that are not so the book offers a retry.
     *
     * That is the *"then prompt to delete and redownload"* half, arrived at from the local side. The button
     * is labelled for what it does.
     */
    fun onVerify() {
        viewModelScope.launch {
            _message.value = when (val report = verification.verifyFully()) {
                is AppResult.Failure -> report.error.summary
                is AppResult.Success -> report.value.summary()
            }
        }
    }

    /* PD-003 / PRODUCT_SPEC DL-006 — one device-level pin protects the shared physical copy. */

    /**
     * BW-DL-03 / #18 — execute only the recovery action owned by the row's presentation state.
     *
     * Pause applies to every in-flight state (Queued, Running, Waiting, Retrying). Paused and terminal Failed
     * go through [DownloadRowActions.download], which preserves an existing manifest/file rows while re-checking the
     * active profile's download permission and current free space. Complete exposes no row action. The
     * state-to-action mapping is the domain's `recoveryAction()`, shared with the Book button.
     */
    fun onRecoveryAction(bookId: LibraryItemId, recoveryState: DownloadRecoveryState) {
        val action = recoveryState.recoveryAction() ?: return
        viewModelScope.launch {
            val result = when (action) {
                DownloadRecoveryAction.Pause -> actions.pause(bookId)

                DownloadRecoveryAction.Resume,
                DownloadRecoveryAction.Retry,
                -> actions.download(bookId)
            }
            if (result is AppResult.Failure) _message.value = result.error.summary
        }
    }

    /** #22 — explicit destructive recovery. Normal Retry/Resume never calls this path. */
    fun onDiscardPartials(bookId: LibraryItemId, serverId: ServerId) {
        viewModelScope.launch {
            when (val discarded = files.discardPartials(serverId, bookId)) {
                is AppResult.Failure -> _message.value = discarded.error.summary

                is AppResult.Success -> {
                    _message.value = "Discarded ${formatByteCount(discarded.value)} of partial download data."
                }
            }
        }
    }

    fun onPinnedChanged(bookId: LibraryItemId, serverId: ServerId, isPinned: Boolean) {
        viewModelScope.launch {
            downloads.setPinned(serverId, bookId, isPinned)
        }
    }

    private fun VerificationReport.summary(): String = if (isIntact) {
        "Checked $filesChecked file(s) in $booksChecked book(s). Everything is where it should be."
    } else {
        "$booksBroken book(s) are missing files and now offer a retry. Nothing was deleted."
    }

    private fun OfflineBook.toRow(
        book: Book?,
        activeProfileId: ProfileId?,
        execution: DownloadExecutionSnapshot?,
        partialBytes: Long,
        storageState: DownloadStorageState,
    ): DownloadRow {
        val recovery = DownloadRecoveryPolicy.resolve(
            durableState = state,
            manifestFilesComplete = isComplete,
            safeFailureSummary = failureSummary,
            executionEvidence = execution?.evidence,
        )
        val progress = execution?.progress ?: durableDownloadProgress()
        val claimedByActiveProfile = activeProfileId != null && activeProfileId in requestedBy
        val sharedWithAnotherProfile =
            claimedByActiveProfile && requestedBy.any { profileId -> profileId != activeProfileId }
        val onDeviceForAnotherProfile =
            activeProfileId != null && !claimedByActiveProfile && requestedBy.isNotEmpty()
        return DownloadRow(
            bookId = itemId,
            serverId = serverId,
            // PRODUCT_SPEC 5.2 — the title only for a book this profile may see. `null` renders as a size
            // without a name, which is enough to decide to delete it.
            title = book?.title,
            author = book?.authors?.firstOrNull()?.name,
            fileCount = files.size,
            bytes = downloadedBytes,
            isComplete = isComplete,
            recoveryState = recovery.state,
            // A title-hidden row keeps the generic recovery state but never carries failure text into UI.
            // Even sanitized infrastructure text is not permission to reveal another profile's media context.
            failureSummary = recovery.failureSummary.takeIf { book != null },
            isPinned = isPinned,
            isClaimedByActiveProfile = claimedByActiveProfile,
            isSharedWithAnotherProfile = sharedWithAnotherProfile,
            isOnDeviceForAnotherProfile = onDeviceForAnotherProfile,
            partialBytes = partialBytes,
            progress = progress.takeUnless { recovery.state == DownloadRecoveryState.Complete },
            storageState = storageState,
        )
    }

    private fun storageState(volumeUuid: String?, available: Set<String>): DownloadStorageState = when {
        volumeUuid == null -> DownloadStorageState.Unknown
        volumeUuid in available -> DownloadStorageState.Available
        else -> DownloadStorageState.Unavailable
    }

    private fun formatByteCount(bytes: Long): String = when {
        bytes >= BYTES_PER_GIBIBYTE -> String.format(
            java.util.Locale.US,
            "%.1f GiB",
            bytes / BYTES_PER_GIBIBYTE.toDouble(),
        )

        bytes >= BYTES_PER_MEBIBYTE -> String.format(
            java.util.Locale.US,
            "%.1f MiB",
            bytes / BYTES_PER_MEBIBYTE.toDouble(),
        )

        bytes >= BYTES_PER_KIBIBYTE -> String.format(
            java.util.Locale.US,
            "%.1f KiB",
            bytes / BYTES_PER_KIBIBYTE.toDouble(),
        )

        else -> "$bytes B"
    }

    private companion object {
        const val BYTES_PER_KIBIBYTE = 1_024L
        const val BYTES_PER_MEBIBYTE = BYTES_PER_KIBIBYTE * 1_024L
        const val BYTES_PER_GIBIBYTE = BYTES_PER_MEBIBYTE * 1_024L
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val SHARED_COPY_KEPT =
            "Removed from your downloads. The files stayed, because another profile on this device also " +
                "downloaded this book."
    }
}

/**
 * @property totalBytes what every download occupies, which is the number somebody came to this screen for.
 */
private data class VisibleBooks(val profileId: ProfileId?, val books: List<Book>)

private data class DownloadTransientPresentation(
    val executions: Map<DownloadExecutionKey, DownloadExecutionSnapshot>,
    val partialBytes: Map<DownloadExecutionKey, Long>,
    val availableVolumeUuids: Set<String>,
)

data class DownloadsUiState(
    val books: List<DownloadRow> = emptyList(),
    val totalBytes: Long = 0,
    val isLoaded: Boolean = false,
) {
    val activeBooks: List<DownloadRow>
        get() = books.filter { it.recoveryState != DownloadRecoveryState.Complete }

    val onDeviceBooks: List<DownloadRow>
        get() = books.filter { it.recoveryState == DownloadRecoveryState.Complete }
}

/**
 * One downloaded book.
 *
 * @property title `null` when the active profile may not see this book (PRODUCT_SPEC 5.2). The physical row
 *   is still shown, but profile-scoped Remove is available only when the active profile owns a claim.
 * @property recoveryState the pure BW-DL-02 presentation result. BW-DL-04 may later refine it with transient
 *   execution evidence without persisting WorkManager state.
 * @property failureSummary safe failure copy for a visible failed row; always `null` for title-hidden rows.
 * @property isSharedWithAnotherProfile whether removing it will actually free anything, which is worth
 *   knowing *before* pressing rather than after.
 */
data class DownloadRow(
    val bookId: LibraryItemId,
    val serverId: ServerId,
    val title: String?,
    val author: String?,
    val fileCount: Int,
    val bytes: Long,
    val isComplete: Boolean,
    val recoveryState: DownloadRecoveryState,
    val failureSummary: String?,
    val isPinned: Boolean,
    val isClaimedByActiveProfile: Boolean = true,
    val isSharedWithAnotherProfile: Boolean,
    val isOnDeviceForAnotherProfile: Boolean = false,
    val partialBytes: Long = 0L,
    val progress: DownloadProgress? = null,
    val storageState: DownloadStorageState = DownloadStorageState.Unknown,
) {
    val isFailed: Boolean get() = recoveryState == DownloadRecoveryState.Failed

    /** PRODUCT_SPEC DL-001 — stopped by the listener, not by a failure. The row must not conflate them. */
    val isPaused: Boolean get() = recoveryState == DownloadRecoveryState.Paused

    val canDiscardPartials: Boolean
        get() = partialBytes > 0L &&
            (recoveryState == DownloadRecoveryState.Paused || recoveryState == DownloadRecoveryState.Failed)
}
