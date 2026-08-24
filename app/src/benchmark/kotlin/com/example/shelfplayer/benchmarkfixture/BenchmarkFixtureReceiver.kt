package com.example.shelfplayer.benchmarkfixture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PRODUCT_SPEC 17.3 — how a macrobenchmark gets a 2,000-book library onto a device.
 *
 * ### Why a broadcast
 *
 * A macrobenchmark runs in its own process and cannot reach into the application's. It *can* run shell
 * commands, so the fixture is requested the way the shell can request anything: `am broadcast`. Started as
 * an activity instead, the seeding would leave a task on the stack and the very next cold-start
 * measurement would be measuring a warm process.
 *
 * `goAsync()` is what makes it usable as a step in a test rather than a hope. `am broadcast -W` waits for
 * the receiver to finish, and with a pending result that means waiting for the database write, not for
 * `onReceive` to return. The result code carries the number of books written, so the benchmark can assert
 * it got the library it asked for instead of silently measuring an empty one.
 *
 * ### Why it is safe despite being exported
 *
 * Two independent reasons, either sufficient:
 *
 *  - **It exists in one build type.** `app/src/benchmark/` is compiled into the `benchmark` variant only.
 *    Neither the debug build the owner installs nor any release build contains this class, and there is
 *    no flag that turns it on in one.
 *  - **It requires `WRITE_SECURE_SETTINGS`.** Declared on the receiver in this source set's manifest.
 *    The shell holds that permission; an installed application cannot be granted it. So even inside the
 *    benchmark variant, the only caller that can reach this is the one attached over adb.
 *
 * It is worth being explicit about what the guard is for: this receiver rewrites the library and switches
 * the active profile. On a build that shipped, an exported receiver that did that would be a serious
 * defect, and "it is only for benchmarks" is the kind of reassurance that stops being true. The build-type
 * boundary is what makes it structurally true.
 */
@AndroidEntryPoint
class BenchmarkFixtureReceiver : BroadcastReceiver() {

    @Inject
    lateinit var seeder: BenchmarkLibrarySeeder

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SEED) return

        val bookCount = intent.getIntExtra(EXTRA_BOOK_COUNT, DEFAULT_BOOK_COUNT)
        val seed = intent.getIntExtra(EXTRA_SEED, BenchmarkLibrarySeeder.DEFAULT_SEED)
        val pending = goAsync()

        // The application scope rather than a scope created here: this write must outlive `onReceive`,
        // and PRODUCT_SPEC 22.10 sanctions exactly that injected scope for work that does.
        scope.launch {
            try {
                val written = seeder.seed(bookCount = bookCount, seed = seed)
                pending.resultCode = written
            } finally {
                // In `finally` so a failed seed still releases `am broadcast -W`. Without this a broken
                // fixture would present as a benchmark that hangs, which is the hardest failure to read.
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SEED: String = "com.example.shelfplayer.benchmark.SEED_LIBRARY"
        const val EXTRA_BOOK_COUNT: String = "bookCount"
        const val EXTRA_SEED: String = "seed"

        /** ADR-0025's figure, and PRODUCT_SPEC 17.3's: *"2,000-item fixture library"*. */
        const val DEFAULT_BOOK_COUNT: Int = 2_000
    }
}
