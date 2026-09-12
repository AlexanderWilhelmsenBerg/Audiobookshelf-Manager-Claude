package com.example.shelfplayer.feature.loopbound

import android.content.Context
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The Android host contract that Loopbound's percentage-height CSS and touch viewport depend on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoopboundWebViewTest {

    @Test
    fun `the embedded web view requests the whole host slot`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val webView = createLoopboundWebView(context)

        try {
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, webView.layoutParams.width)
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, webView.layoutParams.height)
        } finally {
            webView.destroy()
        }
    }
}
