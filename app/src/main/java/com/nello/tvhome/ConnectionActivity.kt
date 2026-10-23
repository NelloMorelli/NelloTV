package com.nello.tvhome

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

/** "Dettagli" page for the Wi-Fi / VPN pop-ups: everything about the connection, plus a speed test. */
class ConnectionActivity : GlassPage() {

    private lateinit var network: NetworkMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        network = NetworkMonitor(this) {}
        title("Connessione", "Stato della rete della Fire TV")

        header("Rete")
        network.details().forEach { (k, v) -> info(k, v) }

        header("Internet")
        val publicIp = info("IP pubblico", "…")
        val ping = info("Ping", "…")
        val download = info("Download", "—")

        header("Azioni")
        val test = item("Test velocità", "circa 10 s") { runSpeedTest(it as android.view.View, download) }
        item("Impostazioni rete Fire TV", "›") {
            runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        }
        test.post { test.requestFocus() }

        lifecycleScope.launch {
            publicIp.text = network.publicIp() ?: "non disponibile"
            ping.text = network.ping()?.let { "$it ms" } ?: "non riuscito"
        }
    }

    private var testing = false

    private fun runSpeedTest(row: android.view.View, out: TextView) {
        if (testing) return
        testing = true
        out.text = "test in corso…"
        lifecycleScope.launch {
            val mbps = network.downloadMbps()
            out.text = mbps?.let { String.format(Locale.ITALY, "%.0f Mbps", it) } ?: "non riuscito"
            testing = false
        }
    }
}
