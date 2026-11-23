package com.example.shelfplayer.playback

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * PD-009 refinement 2026-11-16 — a car-class hands-free link connected moments ago.
 *
 * Hardware captures put that link about ten seconds before Android Auto's focus request, and Auto's own
 * controller bind three or more seconds after it. The window is an inference that a car is arriving, never
 * Play authority: [CarArrivalResumeGate.onInferredCarArrival] still needs the pending trusted focus loss, and
 * the service still checks audio mode. One window supports one inferred arrival.
 *
 * Not thread-safe by design: the receiver and the service both run on the main thread.
 */
internal class CarBluetoothArrivalWindow(private val now: () -> Duration) {
    private class Link(val openedAt: Duration, val peer: String?, val preferenceRefresh: PreferenceRefresh?) {
        var consumed = false
        var takeBackClaimed = false
    }

    private class PreferenceRefresh {
        var claimed = false
    }

    private var link: Link? = null

    /** [peerKey] is a process-private correlation token; it is compared, never logged or stored elsewhere. */
    fun onCarHandsFreeConnected(peerKey: String?) {
        val refresh = peerKey?.let { peer ->
            link?.takeIf { it.peer == peer }?.preferenceRefresh ?: PreferenceRefresh()
        }
        link = Link(now(), peerKey, refresh)
    }

    /** One early request per identified car connection, even if a duplicate opens another inference window. */
    fun claimPreferenceRefresh(): (() -> Boolean)? {
        val opened = current() ?: return null
        val refresh = opened.preferenceRefresh?.takeUnless { it.claimed } ?: return null
        refresh.claimed = true
        return {
            current()?.preferenceRefresh === refresh && now() - opened.openedAt in Duration.ZERO..<LIFETIME
        }
    }

    /** The same car leaving ends its window; an unrelated or unreadable peer does not. */
    fun onHandsFreeDisconnected(peerKey: String?) {
        if (peerKey != null && peerKey == link?.peer) close()
    }

    /** Age of an open, unconsumed window; null once it is closed, spent or older than [LIFETIME]. */
    fun age(): Duration? = current()?.takeUnless { it.consumed }?.let { now() - it.openedAt }

    fun consume(): Boolean {
        val current = current()?.takeUnless { it.consumed } ?: return false
        current.consumed = true
        return true
    }

    /** One route take-back per car link, independent of whether the arrival inference was spent. */
    fun claimTakeBack(): Boolean {
        val current = current()?.takeUnless { it.takeBackClaimed } ?: return false
        current.takeBackClaimed = true
        return true
    }

    /** A delayed route callback cannot borrow a replacement car link or renew its original deadline. */
    fun takeBackScope(): TakeBackScope? = current()?.takeUnless { it.takeBackClaimed }?.let { expected ->
        TakeBackScope(
            isCurrent = { current() === expected },
            claim = { current() === expected && claimTakeBack() },
        )
    }

    class TakeBackScope internal constructor(val isCurrent: () -> Boolean, val claim: () -> Boolean)

    fun isOpen(): Boolean = current() != null

    private fun current(): Link? = link?.takeIf { now() - it.openedAt in Duration.ZERO..<LIFETIME }

    fun close() {
        link = null
    }

    internal companion object {
        /** Same bound as the trusted arrival episode; measured link-to-focus intervals were about ten seconds. */
        val LIFETIME: Duration = 30.seconds
    }
}
