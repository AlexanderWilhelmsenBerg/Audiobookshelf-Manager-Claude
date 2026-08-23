package com.example.shelfplayer.feature.settings

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.R
import com.example.shelfplayer.launcher.LauncherIcon
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PRODUCT_SPEC SET-003 — a saved launcher choice remains visible on a narrow Settings screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class LauncherIconPickerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a selected icon at the end of the row starts on screen`() {
        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(180.dp)) {
                    LauncherIconPicker(selected = LauncherIcon.Monochrome, onSelected = {})
                }
            }
        }

        val label = ApplicationProvider.getApplicationContext<Context>().getString(R.string.settings_icon_monochrome)
        composeRule.onNodeWithText(label).assertIsDisplayed().assertIsSelected()
    }
}
