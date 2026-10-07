package com.example.shelfplayer.garmin

import com.example.shelfplayer.core.common.time.AppClock
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GarminGoldenContractTest {
    @Test
    fun commonAndroidAndWatchEnvelopeFixtures() {
        val json = requireNotNull(javaClass.getResource("/garmin/transport-v1.json")).readText()
        val cases = JSONArray(json)
        val codec = GarminMessageCodec(object : AppClock {
            override fun now(): Instant = Instant.ofEpochMilli(1_700_000_000_000L)
            override fun elapsed(): Duration = Duration.ZERO
        })
        repeat(cases.length()) { index ->
            val case = cases.getJSONObject(index)
            assertEquals(
                case.getBoolean("accepted"),
                codec.decode(nativeValue(case.getJSONObject("raw"))) is
                    GarminDecodeResult.Success,
                case.getString("name"),
            )
        }
    }

    private fun nativeValue(value: Any): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { nativeValue(value.get(it)) }
        is JSONArray -> List(value.length()) { nativeValue(value.get(it)) }
        else -> value
    }
}
