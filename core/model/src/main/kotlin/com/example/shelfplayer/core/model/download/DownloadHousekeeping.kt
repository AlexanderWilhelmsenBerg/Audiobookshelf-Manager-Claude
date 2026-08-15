package com.example.shelfplayer.core.model.download

/**
 * PRODUCT_SPEC DL-005 / DL-006 / ADR-0018 decisions 1 and 7 — the two things the app may do to a
 * download without being asked at the time.
 *
 * Both are **off by default**, and that is not timidity. Fetching a book nobody asked for spends storage
 * and possibly data; deleting one is the most destructive thing this app can do on its own. Each is a
 * reasonable thing to want and a rude thing to assume.
 *
 * @property smartDownload the owner's decision 1: at the halfway mark of the current book, fetch the next
 *   in the same series.
 * @property deleteFinishedAfterDays decision 7: remove a finished book this many days after it was
 *   finished. `0` is off. Days rather than hours because a listener who finishes a book at midnight and
 *   wants to replay a chapter over breakfast has not asked for it to be gone.
 * @property deletePreviousOnSmartDownload decision 7's second half: when smart download brings book 7,
 *   remove book 5 — the one *before* the one being listened to, never the current one.
 */
data class DownloadHousekeeping(
    val smartDownload: Boolean = false,
    val deleteFinishedAfterDays: Int = 0,
    val deletePreviousOnSmartDownload: Boolean = false,
) {
    val deletesFinished: Boolean get() = deleteFinishedAfterDays > 0

    companion object {
        val Default: DownloadHousekeeping = DownloadHousekeeping()

        /**
         * PRODUCT_SPEC DL-005 — how far into a book counts as "halfway".
         *
         * The owner's words: *"So, 50% in book 6 will trigger download of book 7."* Expressed as a
         * fraction rather than as a time because it is the only place in this app where a percentage is
         * the right unit — the question is *how much of this book is left to listen to*, not *is this book
         * finished*, which is why ADR-0013 refused a percentage and this does not.
         */
        const val SMART_DOWNLOAD_AT: Float = 0.5f

        /** The choices the settings screen offers for [deleteFinishedAfterDays]. `0` is "never". */
        val RetentionDays: List<Int> = listOf(0, 1, 7, 30)
    }
}
