package dev.kiro.android.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Signals "connectivity just came back", nothing more.
 *
 * The callback is registered **once, for the process's lifetime**, not once per
 * wait: `NetworkCallback.onAvailable` fires immediately with the current state
 * on every fresh registration, so a phone that already has a network — the
 * common case — would report "regained" on every single registration and
 * reset the backoff before it ever got to wait. Tracking the down-then-up edge
 * across one permanent registration is what makes this fire only on an actual
 * transition, verified live: without it, the reconnect loop's attempt counter
 * on [ConnectionState.Reconnecting][dev.kiro.core.session.ConnectionState.Reconnecting]
 * got stuck at 1 and retried in a tight loop on an emulator with working WiFi.
 */
class ConnectivityObserver(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val regained = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // Seeded from the real current state so a device that starts out offline
    // still reports its first-ever network as "regained" instead of the
    // callback having nothing to compare against.
    private var wasDown = connectivityManager.activeNetwork == null

    /** Emits once per genuine down-to-up transition. Not replayed to late subscribers. */
    val onConnectivityRegained: Flow<Unit> = regained.asSharedFlow()

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                // Only a total loss counts -- losing one of several networks
                // while another still satisfies the request is not a drop.
                if (connectivityManager.activeNetwork == null) {
                    wasDown = true
                }
            }

            override fun onAvailable(network: Network) {
                if (wasDown) {
                    wasDown = false
                    regained.tryEmit(Unit)
                }
            }
        })
    }
}
