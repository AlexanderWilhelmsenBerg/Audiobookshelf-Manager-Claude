package com.example.shelfplayer.feature.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.graphics.createBitmap
import com.example.shelfplayer.core.designsystem.theme.ShelfPlayerTheme
import com.example.shelfplayer.core.model.playback.SleepTimerScheduleSettings
import com.example.shelfplayer.garmin.GarminDeviceUi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassSettingsRenderScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "en-w320dp-h1000dp")
    fun compact320() = capture("glass-settings-320")

    @Test
    @Config(qualifiers = "en-w375dp-h1000dp")
    fun compact375() = capture("glass-settings-375")

    @Test
    @Config(qualifiers = "en-w414dp-h1000dp")
    fun compact414() = capture("glass-settings-414")

    @Test
    @Config(qualifiers = "en-w768dp-h1000dp")
    fun wide768() = capture("glass-settings-768")

    @Test
    @Config(qualifiers = "nb-rNO-w320dp-h1000dp", fontScale = 2.0f)
    fun largeText() = capture("glass-settings-nb-320-font2", "Testklokke")
    private fun capture(name: String, watch: String = "Fixture watch") {
        lateinit var view: View
        compose.setContent {
            ShelfPlayerTheme(darkTheme = true, dynamicColor = false) {
                view = LocalView.current
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        GarminDeviceCard(
                            GarminDeviceActions(
                                state = GarminDeviceUi(
                                    name = watch,
                                    connected = true,
                                    paired = true,
                                    configured = true,
                                ),
                            ),
                        )
                        SleepScheduleCard(
                            SleepTimerScheduleSettings.Default.copy(enabled = true),
                            SleepScheduleSettingsActions(),
                        )
                    }
                }
            }
        }
        compose.onNodeWithText(watch).performClick()
        lateinit var image: Bitmap
        compose.runOnIdle {
            image = createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
        }
        val output = File("build/glass-settings-evidence").apply { mkdirs() }
        File(output, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
