package com.example.shelfplayer.core.model

/**
 * PRODUCT_SPEC SET-002 (Privacy/diagnostics) — what is actually on this device, as a number.
 *
 * ### Why this exists
 *
 * Several of Phase 1's acceptance checks cannot be answered by looking at the app, because the
 * requirement is about what was *stored* rather than what is shown. "Unauthorized libraries never appear"
 * is really "unauthorized rows were never written", and a UI that hides a row looks identical to one that
 * never had it. Until now the only way to tell was `adb shell run-as … sqlite3`, which needs a computer,
 * a cable and a device that ships `sqlite3`.
 *
 * ### What it deliberately does not contain
 *
 * Counts, never contents — for everything outside what the active profile may see. [librariesStored] is a
 * number rather than a list of names for exactly the reason the number is interesting: some of those rows
 * may belong to a library this profile is not granted, and printing their names on screen to prove they
 * are hidden would be a strange way to keep them hidden (PRODUCT_SPEC 5.2).
 *
 * [storedCredentials] is a count for a stronger reason: it exists so signing out can be *seen* to have
 * deleted something, and a credential store that could describe its contents to a screen would be a worse
 * store (PRODUCT_SPEC AUTH-003).
 *
 * @property serversStored rows in `servers`. Two accounts on one server must produce one.
 * @property profilesStored rows in `profiles`, whether signed in or not.
 * @property storedCredentials encrypted credential files on disk. Signing one profile out lowers this.
 * @property librariesStored every library row, regardless of which profile may see it.
 * @property librariesAccessible how many of those the active profile is granted.
 * @property booksStored live book rows — everything not soft-deleted.
 * @property booksAccessible how many of those the active profile may see.
 * @property booksSoftDeleted rows kept after the server stopped listing them (PRODUCT_SPEC 13.2).
 * @property progressRecords progress rows belonging to the active profile.
 * @property unsyncedProgressRecords progress this device has not sent to the server yet.
 */
data class StorageDiagnostics(
    val serversStored: Int = 0,
    val profilesStored: Int = 0,
    val storedCredentials: Int = 0,
    val librariesStored: Int = 0,
    val librariesAccessible: Int = 0,
    val booksStored: Int = 0,
    val booksAccessible: Int = 0,
    val booksSoftDeleted: Int = 0,
    val progressRecords: Int = 0,
    val unsyncedProgressRecords: Int = 0,
)
