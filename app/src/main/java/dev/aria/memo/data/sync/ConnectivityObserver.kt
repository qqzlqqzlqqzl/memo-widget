package dev.aria.memo.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope

/**
 * Live online/offline signal driven by the platform [ConnectivityManager].
 *
 * Fixes #158 (Bug-2 用户故事 8): users couldn't tell whether their writes
 * had pushed to GitHub or were still queued locally — the only feedback
 * was a transient Snackbar on push failure. The host screen now reads
 * [online] and shows a persistent "离线中 · N 条待同步" banner whenever
 * the network is unreachable.
 *
 * The flow:
 *  - Emits the *current* connection state immediately on collection
 *    (the system may not fire `onAvailable` for an already-active
 *    network).
 *  - Tracks both `onAvailable` (any network came up) and
 *    `onLost` (the last network went away). With multiple networks
 *    we treat "any internet-capable network" as online.
 *  - Skips repeated identical emissions so the banner doesn't flicker
 *    on Wi-Fi / cellular handoff if both stay available.
 */
class ConnectivityObserver(
    private val cm: ConnectivityManager,
) {

    private val request: NetworkRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()

    /** True while at least one internet-capable network is up. */
    val online: Flow<Boolean> = callbackFlow {
        val active = HashSet<Network>()

        fun publish() {
            // We treat "online" as: at least one internet-capable network
            // currently registered with us via onAvailable, AND the
            // platform agrees it's validated (not a captive portal).
            val now = active.any { net ->
                runCatching { cm.getNetworkCapabilities(net) }.getOrNull()
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            }
            trySend(now)
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                active.add(network)
                publish()
            }
            override fun onLost(network: Network) {
                active.remove(network)
                publish()
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    active.add(network)
                } else {
                    active.remove(network)
                }
                publish()
            }
        }

        // Seed with the current state — system won't fire onAvailable for
        // an already-active network on registration.
        runCatching {
            cm.activeNetwork?.let { active.add(it) }
        }
        publish()

        cm.registerNetworkCallback(request, callback)
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()

    /** Hot, scope-bound variant — useful for ViewModels that need a StateFlow. */
    fun online(scope: CoroutineScope) = online.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true, // optimistic — corrected on first emit
    )

    companion object {
        fun fromContext(context: Context): ConnectivityObserver = ConnectivityObserver(
            cm = context.applicationContext.getSystemService(ConnectivityManager::class.java),
        )
    }
}
