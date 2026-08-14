package com.example.shelfplayer.core.common.connectivity

import kotlinx.coroutines.flow.Flow

/**
 * PRODUCT_SPEC LIB-002 / 6.3 — whether this device currently has a usable network.
 *
 * ### Why an interface here rather than `ConnectivityManager` at the call site
 *
 * The same reason [com.example.shelfplayer.core.common.time.AppClock] exists: `:core:common` is a JVM
 * library and the callers are domain and presentation code that has to be testable without a device.
 * The Android implementation lives in `:app`, the same seam shape as `LogSink`/`AndroidLogSink`.
 *
 * ### What "online" means, and what it does not
 *
 * It means Android believes a network is present and validated. It does **not** mean the user's server
 * is reachable — a self-hosted server on a LAN the phone has just left is unreachable over a perfectly
 * good mobile connection, and a captive portal validates nothing.
 *
 * So this answers exactly one question: *is it worth trying?* PRODUCT_SPEC LIB-002 asks for offline to
 * be a state distinct from error, and the distinction is about which sentence the user is shown —
 * "you are offline" versus "the server said no". A failure that happens while [isOnline] is `false` is
 * the first; anything else is the second. Server reachability is a separate probe (P1-11) and must not
 * be conflated with this one.
 */
interface NetworkMonitor {
    /**
     * Emits the current state immediately on collection, then on every change.
     *
     * Conflated rather than buffered: a rapid flap between networks should leave a collector on the
     * latest truth, not replay a queue of transitions it can no longer act on.
     */
    val isOnline: Flow<Boolean>

    /**
     * PRODUCT_SPEC DL-004 — "Android network metering state is the source of truth".
     *
     * `true` for Wi-Fi and Ethernet, `false` for cellular and for a Wi-Fi network the user has marked as
     * metered — which is the case a naive "is it Wi-Fi" check gets wrong, and the case a tethered phone
     * produces every time.
     *
     * `false` while offline. There is no third value, because every caller's question is *may I spend
     * bytes*, and the answer with no connection is no either way.
     */
    val isUnmetered: Flow<Boolean>
}
