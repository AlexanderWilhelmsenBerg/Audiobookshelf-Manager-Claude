package com.example.shelfplayer.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.DownloadState
import com.example.shelfplayer.core.model.download.OfflineBook
import com.example.shelfplayer.core.model.download.VerificationReport
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.domain.download.OfflineFiles
import com.example.shelfplayer.domain.download.OfflineVerification
import com.example.shelfplayer.domain.repository.DownloadRepository
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
 * listed with its size and without its name, because the whole reason the list exists is to be able to
 * delete it — and a row nobody can name is exactly the row somebody needs to remove.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloads: DownloadRepository,
    private val files: OfflineFiles,
    private val verification: OfflineVerification,
    private val profiles: ProfileRepository,
    library: LibraryRepository,
) : ViewModel() {

    private val visibleBooks = profiles.observeActiveProfile().flatMapLatest { profile ->
        if (profile == null) flowOf(emptyList()) else library.observeAccessibleBooks(profile.id)
    }

    val uiState: StateFlow<DownloadsUiState> = combine(
        downloads.observeAll(),
        downloads.observeTotalBytes(),
        visibleBooks,
    ) { stored, totalBytes, books ->
        val byId = books.associateBy(Book::id)
        DownloadsUiState(
            books = stored.map { copy -> copy.toRow(byId[copy.itemId]) },
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

    /** PRODUCT_SPEC DL-003 — releases this profile's claim, and the files if it was the last. */
    fun onRemove(bookId: LibraryItemId, serverId: ServerId) {
        viewModelScope.launch {
            val profileId = profiles.activeProfileId() ?: return@launch
            when (val removed = files.remove(profileId, serverId, bookId)) {
                is AppResult.Failure -> _message.value = removed.error.summary
                is AppResult.Success -> if (!removed.value) {
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

    /** PRODUCT_SPEC DL-006 — protects one copy from the automatic cleanup, or stops protecting it. */
    fun onPinnedChanged(bookId: LibraryItemId, serverId: ServerId, isPinned: Boolean) {
        viewModelScope.launch {
            val profileId = profiles.activeProfileId() ?: return@launch
            downloads.setPinned(serverId, bookId, profileId, isPinned)
        }
    }

    private fun VerificationReport.summary(): String = if (isIntact) {
        "Checked $filesChecked file(s) in $booksChecked book(s). Everything is where it should be."
    } else {
        "$booksBroken book(s) are missing files and now offer a retry. Nothing was deleted."
    }

    private fun OfflineBook.toRow(book: Book?): DownloadRow = DownloadRow(
        bookId = itemId,
        serverId = serverId,
        // PRODUCT_SPEC 5.2 — the title only for a book this profile may see. `null` renders as a size
        // without a name, which is enough to decide to delete it.
        title = book?.title,
        author = book?.authors?.firstOrNull()?.name,
        fileCount = files.size,
        bytes = downloadedBytes,
        isComplete = isComplete,
        isFailed = state == DownloadState.Failed,
        isPinned = isPinned,
        isSharedWithAnotherProfile = requestedBy.size > 1,
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val SHARED_COPY_KEPT =
            "Removed from your downloads. The files stayed, because another profile on this device also " +
                "downloaded this book."
    }
}

/**
 * @property totalBytes what every download occupies, which is the number somebody came to this screen for.
 */
data class DownloadsUiState(
    val books: List<DownloadRow> = emptyList(),
    val totalBytes: Long = 0,
    val isLoaded: Boolean = false,
)

/**
 * One downloaded book.
 *
 * @property title `null` when the active profile may not see this book (PRODUCT_SPEC 5.2). The row is still
 *   shown and still deletable — that is the point of decision 6.
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
    val isFailed: Boolean,
    val isPinned: Boolean,
    val isSharedWithAnotherProfile: Boolean,
)
