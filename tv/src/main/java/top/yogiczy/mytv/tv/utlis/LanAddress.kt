package top.yogiczy.mytv.tv.utlis

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.net.Inet4Address

internal fun observeLanAddress(context: Context): Flow<String?> {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return flowOf(null)
    return callbackFlow {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(Unit) }
            override fun onLost(network: Network) { trySend(Unit) }
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) { trySend(Unit) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(Unit) }
        }

        val registered = runCatching { connectivity.registerNetworkCallback(request, callback) }.isSuccess
        trySend(Unit)
        if (!registered) close()
        awaitClose {
            if (registered) connectivity.unregisterNetworkCallback(callback)
        }
    }
        .conflate()
        // Read the snapshot in the collector, outside the system callback thread.
        .map { runCatching { connectivity.lanAddress() }.getOrNull() }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)
}

@Suppress("DEPRECATION") // Enumerate connected LANs even when a VPN owns the default network.
private fun ConnectivityManager.lanAddress(): String? {
    val active = activeNetwork
    return allNetworks.asSequence()
        .mapNotNull { network ->
            val capabilities = getNetworkCapabilities(network) ?: return@mapNotNull null
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val priority = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 2
                else -> return@mapNotNull null
            }
            val address = getLinkProperties(network)?.linkAddresses.orEmpty()
                .asSequence()
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .firstOrNull {
                    !it.isAnyLocalAddress && !it.isLoopbackAddress &&
                        !it.isLinkLocalAddress && !it.isMulticastAddress
                }?.hostAddress ?: return@mapNotNull null
            (if (network == active) 0 else priority) to address
        }
        .minWithOrNull(compareBy({ it.first }, { it.second }))
        ?.second
}
