package com.example.shelfplayer.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.getSystemService
import com.example.shelfplayer.core.common.connectivity.NetworkMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC LIB-002 / 6.3 — [NetworkMonitor] against `ConnectivityManager`.
 *
 * The `ACCESS_NETWORK_STATE` permission has been in the manifest since Phase 0 and nothing used it;
 * this is what it was declared for.
 *
 * ### Why a callback rather than a poll
 *
 * `activeNetwork` read on demand answers "was there a network when you asked", and every caller then
 * has to decide when to ask again. A callback answers "there is one now", which is what a
 * refresh-on-reconnect needs: the interesting moment is the *transition*, and a poll can only find it
 * by looking often enough to waste battery.
 *
 * ### `NET_CAPABILITY_VALIDATED`, and the honest limit of it
 *
 * A network that is merely *connected* can be a captive portal or a router with no upstream. Android
 * probes for real reachability and reports it as `VALIDATED`, so that is what is required here rather
 * than the weaker `NET_CAPABILITY_INTERNET`.
 *
 * It is still only a claim about the internet, not about the user's server. A self-hosted server on a
 * LAN the phone has just left is unreachable over a perfectly good mobile connection that validates
 * fine. This monitor deliberately does not try to bridge that gap — conflating "the device has a
 * network" with "your server answers" would produce a green indicator pointing at a server that is not
 * there, which is worse than no indicator. Server reachability is its own probe.
 */
@Singleton
class AndroidNetworkMonitor @Inject constructor(@param:ApplicationContext private val context: Context) :
    NetworkMonitor {

    override val isOnline: Flow<Boolean> = callbackFlow {
        val manager = context.getSystemService<ConnectivityManager>()
        if (manager == null) {
            // No connectivity service is not the same as being offline, and reporting `false` would
            // strand the UI in an offline state it can never leave. Assume online and let the actual
            // request fail with something the error mapper can describe.
            trySend(true)
            awaitClose {}
            return@callbackFlow
        }

        // A set rather than a boolean: a device with Wi-Fi and mobile both up delivers two `onAvailable`
        // callbacks and then one `onLost` when the first drops. Tracking a flag would report offline
        // while a perfectly good second network was still carrying traffic.
        val available = mutableSetOf<Network>()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                available += network
                trySend(available.isNotEmpty())
            }

            override fun onLost(network: Network) {
                available -= network
                trySend(available.isNotEmpty())
            }

            override fun onUnavailable() {
                trySend(false)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        // The current state, before any callback arrives. Without it a collector that starts while
        // already connected waits for the next transition, which on a stable connection never comes.
        trySend(manager.hasValidatedNetwork())
        manager.registerNetworkCallback(request, callback)

        awaitClose { manager.unregisterNetworkCallback(callback) }
    }
        .distinctUntilChanged()
        .conflate()

    /**
     * PRODUCT_SPEC DL-004 — the metering state, from the platform rather than from the transport type.
     *
     * `NET_CAPABILITY_NOT_METERED` rather than "is the transport Wi-Fi". The two disagree in both
     * directions and both cases are common: a Wi-Fi network the user marked as metered — a phone hotspot,
     * a hotel connection with a cap — reports Wi-Fi and is metered, and an unmetered mobile plan reports
     * cellular. The requirement says the platform's answer is the source of truth, and this is it.
     *
     * `NET_CAPABILITY_NOT_VPN` is deliberately *not* required. A VPN reports its own capabilities and its
     * underlying transport's metering, so excluding it would report every VPN user as metered and refuse
     * their downloads on their home Wi-Fi.
     */
    override val isUnmetered: Flow<Boolean> = callbackFlow {
        val manager = context.getSystemService<ConnectivityManager>()
        if (manager == null) {
            // No connectivity service. Reported as metered, which is the direction that cannot cost
            // somebody money — the opposite of [isOnline]'s optimistic fallback, and for the same reason:
            // each guesses towards the answer whose being wrong is harmless.
            trySend(false)
            awaitClose {}
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            }

            override fun onLost(network: Network) {
                trySend(manager.isActiveNetworkUnmetered())
            }

            override fun onUnavailable() {
                trySend(false)
            }
        }

        trySend(manager.isActiveNetworkUnmetered())
        manager.registerDefaultNetworkCallback(callback)

        awaitClose { manager.unregisterNetworkCallback(callback) }
    }
        .distinctUntilChanged()
        .conflate()

    private fun ConnectivityManager.isActiveNetworkUnmetered(): Boolean {
        val capabilities = getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun ConnectivityManager.hasValidatedNetwork(): Boolean {
        val capabilities = getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
