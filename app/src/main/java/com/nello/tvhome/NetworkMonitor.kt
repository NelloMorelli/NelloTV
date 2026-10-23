package com.nello.tvhome

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/** Connection state for the top bar: Wi-Fi/Ethernet, signal, internet reachability, VPN. */
class NetworkMonitor(private val context: Context, private val onChange: (State) -> Unit) {

    data class State(
        val online: Boolean,
        val wifi: Boolean,
        val ethernet: Boolean,
        /** 0..3 */
        val wifiLevel: Int,
        val vpn: Boolean,
    )

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    var state: State = read()
        private set

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = refresh()
    }

    fun start() {
        // every network, not just the default one, so a VPN coming up or down is noticed too
        val request = android.net.NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(request, callback)
        refresh()
    }

    fun stop() = runCatching { cm.unregisterNetworkCallback(callback) }

    /** Called periodically too: Wi-Fi signal changes don't always fire a callback. */
    fun refresh() {
        main.post {
            val s = read()
            if (s != state) {
                state = s
                onChange(s)
            }
        }
    }

    private fun read(): State {
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val vpn = cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        // with a VPN up the active network is the VPN: look at the physical one underneath
        val physical = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
            .firstOrNull { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
        val wifi = physical?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val ethernet = physical?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        @Suppress("DEPRECATION")
        val level = if (wifi) WifiManager.calculateSignalLevel(wifiManager.connectionInfo.rssi, 4) else 0
        return State(online, wifi, ethernet, level, vpn)
    }

    // ---------------------------------------------------------------- details panel

    @Suppress("DEPRECATION")
    fun details(): List<Pair<String, String>> {
        val s = state
        val out = mutableListOf<Pair<String, String>>()
        out += "Stato" to if (s.online) "Connesso a Internet" else "Nessuna connessione a Internet"
        out += "Tipo" to when {
            s.ethernet -> "Ethernet"
            s.wifi -> "Wi-Fi"
            else -> "—"
        }
        if (s.wifi) {
            val info = wifiManager.connectionInfo
            val ssid = info.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            out += "Rete" to (ssid ?: "Wi-Fi")
            out += "Segnale" to "${listOf("Debole", "Discreto", "Buono", "Ottimo")[s.wifiLevel]} (${info.rssi} dBm)"
            if (info.linkSpeed > 0) out += "Velocità collegamento" to "${info.linkSpeed} Mbps"
        }
        localIp()?.let { out += "IP locale" to it }
        out += "VPN" to if (s.vpn) "Attiva" else "Non attiva"
        return out
    }

    private fun localIp(): String? {
        val network = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == false }
            ?: return null
        return cm.getLinkProperties(network)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress
    }

    /** Public IP and country as seen from the internet (through the VPN when it's on). */
    suspend fun publicIp(): String? = runCatching {
        val o = JSONObject(Net.get("https://ipwho.is/?fields=ip,country,city"))
        listOfNotNull(o.str("ip"), listOfNotNull(o.str("city"), o.str("country")).joinToString(", ").takeIf { it.isNotBlank() })
            .joinToString(" · ")
    }.getOrNull()

    /** Best of 3 TCP connects (ms), trying a few well-known hosts in case one is blocked. */
    suspend fun ping(): Long? = withContext(Dispatchers.IO) {
        for (host in listOf("1.1.1.1", "8.8.8.8", "www.google.com")) {
            val ms = runCatching {
                (1..3).minOf {
                    val t = System.nanoTime()
                    Socket().use { s -> s.connect(InetSocketAddress(host, 443), 3000) }
                    (System.nanoTime() - t) / 1_000_000
                }
            }.onFailure { android.util.Log.w("NetworkMonitor", "ping $host", it) }.getOrNull()
            if (ms != null) return@withContext ms
        }
        null
    }

    /** Downloads ~25 MB from Cloudflare's speed test (max 10 s) and returns Mbps. */
    suspend fun downloadMbps(): Double? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("https://speed.cloudflare.com/__down?bytes=25000000").openConnection()
            conn.connectTimeout = 5000
            conn.readTimeout = 10_000
            val buf = ByteArray(64 * 1024)
            var total = 0L
            val start = System.nanoTime()
            conn.getInputStream().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (System.nanoTime() - start > 10_000_000_000L) break
                }
            }
            val seconds = (System.nanoTime() - start) / 1e9
            total * 8 / seconds / 1_000_000
        }.getOrNull()
    }
}
