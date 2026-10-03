package com.example.shelfplayer

import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** PRODUCT_SPEC 17.1 — unit sandboxes must not start process-lifetime production services. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RobolectricApplicationIsolationTest {
    @Test
    fun `unit sandbox uses an application without production background collectors`() {
        assertEquals(Application::class.java, RuntimeEnvironment.getApplication().javaClass)
    }
}
