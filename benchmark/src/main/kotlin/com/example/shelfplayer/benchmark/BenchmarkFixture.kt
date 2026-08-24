package com.example.shelfplayer.benchmark

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals

/**
 * PRODUCT_SPEC 17.3 / ADR-0025 — the fixture and the navigation every benchmark in this module shares.
 *
 * Kept in one file so that a change to how the library is seeded, or to the control a benchmark taps, is
 * made once. Four benchmarks that each grew their own copy of "wait for Home" is how a suite starts
 * measuring four slightly different things and reporting them under one heading.
 */
internal object BenchmarkFixture {

    /** The application under measurement. Not `.debug` — the benchmark variant carries no suffix. */
    const val PACKAGE_NAME: String = "org.homebord.bookwave"

    /** ADR-0025 and PRODUCT_SPEC 17.3: *"2,000-item fixture library"*. */
    const val BOOK_COUNT: Int = 2_000

    private const val SEED_ACTION = "com.example.shelfplayer.benchmark.SEED_LIBRARY"
    private const val SEED_RECEIVER = "$PACKAGE_NAME/com.example.shelfplayer.benchmarkfixture.BenchmarkFixtureReceiver"

    /**
     * The English content descriptions the benchmarks navigate by.
     *
     * English is not an assumption about the device — `BenchmarkLibrarySeeder` pins the application's
     * language when it seeds, precisely so these strings are the ones on screen whatever the phone is set
     * to. They are duplicated from `app/src/main/res/values/strings.xml` because a `com.android.test`
     * module cannot see the application's resources; if one is renamed there, a benchmark fails to find
     * its control and says so, which is the failure mode to want.
     */
    const val SHOW_LIST_DESCRIPTION: String = "Show all books as a list"

    private const val UI_TIMEOUT_MILLIS = 10_000L

    /**
     * Writes the fixture library and blocks until it is on disk.
     *
     * `-W` is what makes this a step rather than a race: the receiver calls `goAsync()`, so the shell waits
     * for the database write to finish and reports the number of books as the broadcast's result code. That
     * figure is asserted, because the alternative to asserting it is a suite that quietly measures an empty
     * library and reports excellent numbers for it.
     */
    fun seedLibrary(device: UiDevice, bookCount: Int = BOOK_COUNT) {
        val output = device.executeShellCommand(
            "am broadcast -W -a $SEED_ACTION -n $SEED_RECEIVER --ei bookCount $bookCount",
        )
        val reported = Regex("result=(-?\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull()
        assertEquals(
            "The fixture receiver did not report the library it was asked for. Shell said: $output",
            bookCount,
            reported,
        )
    }

    /**
     * Waits for Home to be showing content rather than for the window to exist.
     *
     * The view toggle is in the top bar and is only composed once the screen has a state to draw, so its
     * presence is a usable proxy for "the first real emission arrived" without the benchmark needing to
     * know anything about a book's title.
     */
    fun awaitHome(device: UiDevice) {
        device.wait(Until.hasObject(By.desc(SHOW_LIST_DESCRIPTION)), UI_TIMEOUT_MILLIS)
    }

    /**
     * Switches the Books axis from the shelves to the flat list — the screen ADR-0025 re-pointed 17.3's
     * scroll target at, after finding that the grid the requirement describes does not exist in this app.
     */
    fun openBooksList(device: UiDevice) {
        awaitHome(device)
        device.findObject(By.desc(SHOW_LIST_DESCRIPTION))?.click()
        device.waitForIdle()
    }
}
