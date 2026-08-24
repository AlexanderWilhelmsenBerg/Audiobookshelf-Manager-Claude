package com.example.shelfplayer.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PRODUCT_SPEC 17.3 — *"cached library screen interactive under 1 second"*, over a 2,000-book library.
 *
 * ### What the two numbers mean, and why only one of them answers the requirement
 *
 * [StartupTimingMetric] reports `timeToInitialDisplayMs` and `timeToFullDisplayMs`. The first is the first
 * frame of anything — a top bar over nothing. The second is the moment `HomeScreen`'s `ReportDrawnWhen`
 * fires, which is when the shelves or the list have their books. **`timeToFullDisplayMs` is the number
 * 17.3 is asking about**; the initial figure is recorded alongside it because a large gap between them is
 * itself the finding.
 *
 * ### The three compilation modes are not three runs of the same thing
 *
 * A cold start on a freshly installed application is interpreted; the same start after Android has profiled
 * the app is partly compiled. Reporting one number without saying which state produced it is how a startup
 * figure becomes unfalsifiable, so all three are measured:
 *
 *  - [CompilationMode.None] — the worst case, and what the first launch after an install actually costs.
 *  - [CompilationMode.Partial] with the baseline profile — what a user gets from the first launch if
 *    `BaselineProfileGenerator`'s output is shipped. The difference between this and `None` is the whole
 *    argument for shipping one (R-25 puts it at 20–30%).
 *  - [CompilationMode.Full] — the floor. Not shippable; it is the "how much of this is JIT" control.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun seed() {
        BenchmarkFixture.seedLibrary(device)
    }

    @Test
    fun startupNoCompilation() = measure(CompilationMode.None())

    @Test
    fun startupBaselineProfile() = measure(
        // `Require`, not `UseIfAvailable`: a run that silently fell back to no profile would report the
        // `None` number under this test's name, which is the one result worse than a failure.
        CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
    )

    @Test
    fun startupFullCompilation() = measure(CompilationMode.Full())

    private fun measure(compilationMode: CompilationMode) = rule.measureRepeated(
        packageName = BenchmarkFixture.PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric()),
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD,
        compilationMode = compilationMode,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        // Without this the iteration can end at the first frame, and `timeToFullDisplayMs` would be
        // reported from whenever the trace happened to be cut rather than from the library being drawn.
        BenchmarkFixture.awaitHome(device)
    }

    private companion object {
        /**
         * Ten, because Macrobenchmark reports a median and a startup distribution on a phone is wide —
         * background work, thermal state and the scheduler all move it. Fewer iterations produce a number
         * that changes between runs by more than the changes anyone would make to chase it.
         */
        const val ITERATIONS = 10
    }
}
