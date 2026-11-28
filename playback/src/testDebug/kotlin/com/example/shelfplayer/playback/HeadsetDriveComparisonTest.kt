package com.example.shelfplayer.playback

import android.content.Context
import com.example.shelfplayer.core.testing.TestAppClock
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeadsetDriveComparisonTest {
    @Test
    fun `bound transfer gate follows the active case and restores the saved choice after the test`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val trials = HeadsetOutputTrials()
        val takeBack = createHeadsetTakeBack(context, TestAppClock(), trials) { _, _ -> }
        for (saved in listOf(false, true)) {
            AutomaticHeadsetTakeBackSetting.setEnabled(context, saved)
            for ((case, expected) in listOf(
                HeadsetOutputCase.Disconnect500 to false,
                HeadsetOutputCase.ConnectTakeBackOn to true,
                HeadsetOutputCase.Disconnect1000 to false,
                HeadsetOutputCase.ConnectTakeBackOff to false,
                HeadsetOutputCase.Normal to saved,
            )) {
                trials.select(case, "fixture")
                assertEquals(expected, takeBack.automaticTransferEnabled, case.name)
                assertEquals(saved, AutomaticHeadsetTakeBackSetting.enabled(context))
            }
        }
        takeBack.close()
    }
}
