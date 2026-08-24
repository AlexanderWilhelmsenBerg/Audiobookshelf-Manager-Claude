package com.example.shelfplayer.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PRODUCT_SPEC 17.3 / R-25 — generates the baseline profile, on a device.
 *
 * ### What a baseline profile is and why this project can have one cheaply
 *
 * It is a list of the classes and methods a start-up actually touches, shipped in the APK so that Android
 * compiles them ahead of time instead of interpreting them on first launch. R-25 puts it at 20–30% of cold
 * start, and records that **the consuming half is already free**: `androidx.profileinstaller` is on the
 * release classpath transitively, so shipping a profile needs no new dependency — only a file.
 *
 * It cannot be written by hand. It is *recorded* by exercising the app on a device, which is what this
 * class does and why R-25 stayed open until there was hardware.
 *
 * ### What is exercised, and why that list and not more
 *
 * The journey below is start, wait for the library, switch to the flat list, scroll it. That is what the
 * first minute with this app looks like, and a profile is only worth what its journey represents. Adding
 * every screen would produce a larger profile that compiles more code ahead of time, including code most
 * launches never reach — which costs install time and dex size to speed up something that did not happen.
 *
 * ### Installing the output
 *
 * The run writes `baseline-prof.txt` into the module's build outputs; `docs/benchmark.md` has the path and
 * the one command that copies it to `app/src/main/baseline-prof.txt`, where AGP picks it up. It is
 * deliberately a manual copy rather than the `androidx.baselineprofile` Gradle plugin: the plugin wires a
 * device-dependent task into the build graph of a project whose CI has no device, and the copy is a
 * two-second step taken about as often as the app's start-up path changes.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun generate() = rule.collect(packageName = BenchmarkFixture.PACKAGE_NAME) {
        // Seeded inside the collection block rather than in a `@Before`: `collect` reinstalls and clears
        // the application between iterations, so a library written once beforehand would be gone by the
        // time the journey below ran, and the profile would record an empty-library start-up.
        BenchmarkFixture.seedLibrary(device)

        pressHome()
        startActivityAndWait()
        BenchmarkFixture.awaitHome(device)

        BenchmarkFixture.openBooksList(device)
        device.findObject(By.scrollable(true))?.let { list ->
            list.setGestureMargin(device.displayWidth / GESTURE_MARGIN_DIVISOR)
            list.fling(Direction.DOWN)
            device.waitForIdle()
        }
    }

    private companion object {
        const val GESTURE_MARGIN_DIVISOR = 5
    }
}
