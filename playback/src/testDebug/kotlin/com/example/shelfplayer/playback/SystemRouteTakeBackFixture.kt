package com.example.shelfplayer.playback

import android.media.AudioManager
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.testing.TestAppClock

internal class SystemRouteTakeBackFixture {
    val port = Port()
    val clock = TestAppClock()
    val window = CarBluetoothArrivalWindow { clock.elapsed() }
    var current = true
    private var linkStarted = false
    val outcomes = mutableListOf<String>()
    val rendered = mutableListOf<String>()
    val takeBack = SystemRouteHeadsetTakeBack(port, clock) { _, fields ->
        outcomes += fields.filterIsInstance<LogField.Public>().single { it.key == "outcome" }.value
        rendered += fields.joinToString(" ") {
            when (it) {
                is LogField.Public -> "${it.key}=${it.value}"

                is LogField.Count -> "${it.key}=${it.value}"

                is LogField.Millis -> "${it.key}=${it.value}"

                is LogField.ServerHost, is LogField.Username, is LogField.MediaTitle,
                is LogField.FilePath, is LogField.Url, is LogField.Secret, is LogField.Identifier,
                ->
                    error("Unexpected private diagnostic field")
            }
        }
    }

    fun verify(endpoint: String = HEADSET) {
        takeBack.onHeadsetVerified(endpoint) { !window.isOpen() }
    }

    fun startLink() {
        window.onCarHandsFreeConnected("car-link")
        linkStarted = true
    }

    fun displace(endpoint: String = HEADSET) {
        if (!linkStarted) startLink()
        window.takeBackScope()?.let { takeBack.onHeadsetDisplaced(endpoint, it) { current } }
    }

    class Port : SystemRoutePort {
        var status = SystemRouteStatus.Observing
        var mode = AudioManager.MODE_NORMAL
        var routes = listOf(route(HEADSET, selected = true))
        var failTransfer = false
        val requests = mutableListOf<String>()
        var changed: () -> Unit = {}
        var result: (String, SystemRouteStatus) -> Unit = { _, _ -> }

        fun carTakesOver() {
            routes = listOf(route(HEADSET, selected = false), route(CAR, selected = true))
        }

        override fun availability() = status
        override fun audioMode() = mode
        override fun routes() = routes
        override fun open(changed: () -> Unit, result: (String, SystemRouteStatus) -> Unit) {
            this.changed = changed
            this.result = result
        }

        override fun transfer(id: String) {
            check(!failTransfer)
            requests += id
        }

        override fun close() = Unit
    }

    companion object {
        const val HEADSET = "private-headset"
        const val CAR = "private-car"
        fun route(id: String, selected: Boolean) =
            SystemRouteCandidate(id, "private-name", bluetooth = true, system = true, selected = selected)
    }
}
