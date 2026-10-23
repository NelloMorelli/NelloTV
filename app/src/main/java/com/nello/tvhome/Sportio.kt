package com.nello.tvhome

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import kotlinx.coroutines.delay

/**
 * Sport events open in Sportio (IPTV) on the channel that shows them. The channel comes from the
 * TV guide (a programme on a Sky/DAZN/Rai/TV8 channel named after the two teams, starting near
 * kick-off); if the guide doesn't have it, a sensible default for the competition.
 * Sportio plays a channel from `sportio://play/live?sourceId=<playlist>&name=<exact name>`.
 */
object Sportio {
    const val PKG = "tv.sportio.app"
    /** the playlist in Sportio with the "TOP ITALIA" channel names */
    private const val SOURCE = 5

    /** guide channel id → exact channel name in the Sportio playlist, in order of preference */
    val channels = listOf(
        TvChannel("Sky.Sport.Uno.it", "Sky Sport Uno", PKG),
        TvChannel("Sky.Sport.Calcio.it", "Sky Sport Calcio", PKG),
        TvChannel("DAZN.1.it.it", "DAZN 1", PKG),
        TvChannel("DAZN.2.it.it", "[215] DAZN 2", PKG),
        TvChannel("Sky.Sport.F1.it", "Sky Sport F1", PKG),
        TvChannel("Sky.Sport..251.it", "Sky Sport 251", PKG),
        TvChannel("Sky.Sport..252.it", "Sky Sport 252", PKG),
        TvChannel("Sky.Sport..253.it", "Sky Sport 253", PKG),
        TvChannel("Sky.Sport..254.it", "Sky Sport 254", PKG),
        TvChannel("Sky.Sport..255.it", "Sky Sport 255", PKG),
        TvChannel("Sky.Sport..256.it", "Sky Sport 256", PKG),
        TvChannel("Sky.Sport.Arena.it", "Sky Sport Arena", PKG),
        TvChannel("Sky.Sport.Max.it", "Sky Sport Max", PKG),
        TvChannel("Sky.Sport.Mix.it", "Sky Sport Mix", PKG),
        TvChannel("Rai1.it", "RAI 1", PKG),
        TvChannel("Rai2.it", "RAI 2", PKG),
        TvChannel("TV8.HD.it", "TV 8", PKG),
        TvChannel("Canale.5.it", "Canale 5", PKG),
        TvChannel("Italia.1.it", "Italia 1", PKG),
        TvChannel("Sky.Sport.4K.it", "[213] Sky Sport 4K", PKG),
    )

    fun installed(c: Context) = runCatching { c.packageManager.getPackageInfo(PKG, 0) }.isSuccess

    // WireGuard ("Consenti app di controllo remoto" on, CONTROL_TUNNELS granted to us)
    private const val WIREGUARD = "com.wireguard.android"
    const val WG_PERMISSION = "com.wireguard.android.permission.CONTROL_TUNNELS"

    private fun vpnUp(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    /** Turn the WireGuard tunnel on (if it isn't) and wait up to 5 s for it. False if it didn't come up. */
    suspend fun wireguardUp(c: Context, tunnel: String): Boolean {
        if (vpnUp(c)) return true
        runCatching {
            c.sendBroadcast(Intent("com.wireguard.android.action.SET_TUNNEL_UP")
                .setClassName(WIREGUARD, "com.wireguard.android.model.TunnelManager\$IntentReceiver")
                .putExtra("tunnel", tunnel))
        }
        repeat(25) {
            delay(200)
            if (vpnUp(c)) return true
        }
        return false
    }

    fun play(c: Context, channel: String): Boolean = runCatching {
        c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("sportio://play/live?sourceId=$SOURCE&name=${Uri.encode(channel)}"))
            .setPackage(PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    private const val MIN = 60_000L

    /** The channel showing [m]: from the guide, else the competition's usual broadcaster. */
    suspend fun channelFor(c: Context, m: Match): String {
        val home = TeamNames.tokens(m.home) + TeamNames.tokens(m.homeFull)
        val away = TeamNames.tokens(m.away) + TeamNames.tokens(m.awayFull)
        val names = channels.associate { it.id to it.name }
        val live = TvGuide.shows(c).filter { s ->
            s.channel.id in names && s.start in (m.startMillis - 40 * MIN)..(m.startMillis + 15 * MIN) && run {
                val parts = s.title.split(Regex("\\s+[-–]\\s+"), limit = 2)
                parts.size == 2 && TeamNames.overlaps(home, TeamNames.tokens(parts[0])) && TeamNames.overlaps(away, TeamNames.tokens(parts[1]))
            }
        }
        live.minByOrNull { s -> channels.indexOfFirst { it.id == s.channel.id } }?.let { return names[it.channel.id]!! }
        return when {
            PrimeVideo.shows(m) -> "Amazon Prime"
            m.league == League.SERIE_A -> "DAZN 1"
            m.league == League.COPPA || m.league == League.SUPERCOPPA -> "Italia 1"
            else -> "Sky Sport Uno"
        }
    }

    /** F1 sessions: always Sky Sport F1. */
    fun channelFor(@Suppress("UNUSED_PARAMETER") r: RaceSession) = "Sky Sport F1"
}
