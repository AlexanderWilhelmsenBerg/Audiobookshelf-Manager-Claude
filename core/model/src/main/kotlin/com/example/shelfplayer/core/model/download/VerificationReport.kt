package com.example.shelfplayer.core.model.download

/**
 * PRODUCT_SPEC DL-002 / SET-002 — what a verification pass found, as the diagnostics screen shows it.
 *
 * Counts rather than titles. The screen exists to prove the downloads are intact, and naming the books
 * would not make it more convincing (PRODUCT_SPEC 14.5) — while a book whose files went missing is
 * already visible on its own screen, with a retry.
 *
 * @property booksChecked complete downloads only. An incomplete one has nothing to verify: its files are
 *   expected to be missing, and counting it would turn every interrupted download into a warning.
 * @property booksBroken how many were found not to be intact and have been marked for retry. Nothing was
 *   deleted — DL-002 requires user-visible confirmation before removing anything.
 */
data class VerificationReport(val booksChecked: Int = 0, val filesChecked: Int = 0, val booksBroken: Int = 0) {
    val isIntact: Boolean get() = booksBroken == 0
}
