package com.example.shelfplayer.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR-0025 — the two targets that replaced 17.3's grid clause, measured over 2,000 books.
 *
 * ### Why this is not a grid benchmark
 *
 * PRODUCT_SPEC 17.3 asks for *"scrolling grid maintains acceptable Compose performance on 2,000-item
 * fixture library"*. ADR-0025 recorded that a full sweep found no `LazyVerticalGrid`, `LazyHorizontalGrid`
 * or `GridCells` anywhere in this repository: Home is a `LazyColumn` of capped shelves and the flat view is
 * a list. Building a grid so that a benchmark could scroll one would be inventing a screen to satisfy a
 * measurement, which is the failure ADR-0016 cost four phases. So [scrollBooksList] scrolls
 * `BooksView.List`, the screen a user with a large library actually scrolls.
 *
 * ### And why the memory test is here at all
 *
 * It is ADR-0025's second target, added because 17.3 describes a grid rather than an architecture and could
 * not have named the real exposure: **there is no paging**. `LibraryRepository` returns `Flow<List<Book>>`
 * and `HomeViewModel` materialises the whole list on every emission. The ViewModel reasons explicitly about
 * that cost at 490 books; 2,000 is four times what the code was thought about at, and nothing bounds it.
 *
 * ADR-0025 also decided that whether to adopt paging is left to this measurement rather than settled ahead
 * of it — *"the measurement may show paging is unnecessary"*. That is still the position. This class takes
 * the number; it does not argue for a change.
 */
@RunWith(AndroidJUnit4::class)
class LibraryScaleBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun seed() {
        BenchmarkFixture.seedLibrary(device)
    }

    /**
     * PRODUCT_SPEC 17.3 (as re-expressed by ADR-0025) — frame timing while scrolling the flat book list.
     *
     * The number to read is `frameDurationCpuMs` at P95 and P99, and the count of frames over the device's
     * refresh budget. A median is nearly useless here: dropped frames are a tail phenomenon, and a list
     * that janks on one row in fifty has an excellent median.
     */
    @Test
    fun scrollBooksList() = rule.measureRepeated(
        packageName = BenchmarkFixture.PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        iterations = ITERATIONS,
        // WARM, not COLD: this measures scrolling, and paying for a process start inside every iteration
        // would put startup variance into a frame-timing number.
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.DEFAULT,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            BenchmarkFixture.openBooksList(device)
        },
    ) {
        val list = device.findObject(By.scrollable(true))
            ?: error("No scrollable list on Home. The books view toggle did not take effect.")
        // `setGestureMargin` keeps the swipe clear of the system gesture insets at the screen edge, which
        // would otherwise be swallowed by back navigation on a gesture-navigation device and produce a
        // benchmark that measures nothing scrolling.
        list.setGestureMargin(device.displayWidth / GESTURE_MARGIN_DIVISOR)
        repeat(SCROLLS_PER_ITERATION) {
            list.fling(Direction.DOWN)
            device.waitForIdle()
        }
    }

    /**
     * ADR-0025's second target — what Home costs in memory once 2,000 books are materialised.
     *
     * Read `memoryHeapSizeMaxKb` and `memoryRssAnonMaxKb`. There is no threshold asserted here on purpose:
     * ADR-0025 left the paging decision to the number, and a gate written from the first reading would be
     * a threshold chosen to pass rather than one the requirement asked for. `docs/benchmark.md` records the
     * measurement; a gate can follow once there is a baseline to regress against.
     */
    // `MemoryUsageMetric` is the only way to read heap and RSS from a macrobenchmark and it is still
    // experimental. Opted in here rather than module-wide, so the next experimental API to be reached for
    // has to be a decision rather than an inheritance.
    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun homeMemoryAtScale() = rule.measureRepeated(
        packageName = BenchmarkFixture.PACKAGE_NAME,
        metrics = listOf(MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.DEFAULT,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        // Both axes, because they cost differently: the shelves group the library and the flat list
        // materialises it, and the peak is what the requirement is about.
        BenchmarkFixture.awaitHome(device)
        BenchmarkFixture.openBooksList(device)
        device.waitForIdle()
    }

    private companion object {
        const val ITERATIONS = 10
        const val SCROLLS_PER_ITERATION = 3
        const val GESTURE_MARGIN_DIVISOR = 5
    }
}
