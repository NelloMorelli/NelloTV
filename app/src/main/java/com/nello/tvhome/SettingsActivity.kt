package com.nello.tvhome

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast

/** Launcher preferences, read by MainActivity on resume. */
class LauncherPrefs(context: Context) {
    private val p = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** "original", "daily" (TMDB backdrop of the day) or "black" */
    var wallpaper: String
        get() = p.getString("wallpaper", "original")!!
        set(v) = p.edit().putString("wallpaper", v).apply()

    /** always-on screensaver (wallpaper + clock) instead of Amazon's */
    var screensaver: Boolean
        get() = p.getBoolean("screensaver", true)
        set(v) = p.edit().putBoolean("screensaver", v).apply()

    var night: Boolean
        get() = p.getBoolean("night", false)
        set(v) = p.edit().putBoolean("night", v).apply()

    /** bumped every time a new local image is chosen, so the home screen reloads it */
    var customVersion: Long
        get() = p.getLong("customVersion", 0)
        set(v) = p.edit().putLong("customVersion", v).apply()

    /** name of the tab shown at start: SEARCH, EXPLORE, MOVIES, SERIES, SPORT */
    var startTab: String
        get() = p.getString("startTab", "EXPLORE")!!
        set(v) = p.edit().putString("startTab", v).apply()

    var vpnPackage: String?
        get() = p.getString("vpn", null)
        set(v) = p.edit().putString("vpn", v).apply()

    companion object {
        val VPN_APPS = listOf("ch.protonvpn.android" to "ProtonVPN", "com.wireguard.android" to "WireGuard")
        val TABS = listOf("EXPLORE" to "Esplora", "MOVIES" to "Film", "SERIES" to "Serie TV", "TV" to "TV", "SPORT" to "Sport", "SEARCH" to "Cerca")
        val WALLPAPERS = listOf(
            "original" to "Travis Scott (originale)",
            "daily" to "Film o serie del giorno",
            "black" to "Nero",
            "custom" to "Immagine dal dispositivo",
        )
    }
}

class SettingsActivity : GlassPage() {

    private lateinit var prefs: LauncherPrefs
    private lateinit var apps: Apps
    private lateinit var nuvio: NuvioSync
    /** which row to focus after a rebuild */
    private var focusRow = 0

    override fun onResume() {
        super.onResume()
        // back from the image picker: show the new choice
        if (rows.isNotEmpty()) build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = LauncherPrefs(this)
        apps = Apps(this)
        nuvio = NuvioSync(this)
        build()
    }

    private val rows = mutableListOf<View>()

    private fun row(title: String, value: String?, onClick: (View) -> Unit) {
        val index = rows.size
        rows += item(title, value) { focusRow = index; onClick(it) }
    }

    private fun build() {
        list.removeAllViews()
        rows.clear()
        title("Impostazioni")

        header("Nello TV")
        row("Personalizza", "Colori, vetro, schede, cataloghi, nomi…") { startActivity(Intent(this, CustomizeActivity::class.java)) }
        row("Sfondo", LauncherPrefs.WALLPAPERS.first { it.first == prefs.wallpaper }.second) {
            // same menu as the ⚙ pop-up: built-ins, your image (if any), pick a new one
            val hasCustom = java.io.File(filesDir, WallpaperPickerActivity.CUSTOM_FILE).exists()
            val options = LauncherPrefs.WALLPAPERS.filter { (k, _) -> k != "custom" } +
                (if (hasCustom) listOf("custom" to "La tua immagine") else emptyList()) +
                ("pick" to "Scegli immagine dal dispositivo…")
            choose(it, "Sfondo", options, prefs.wallpaper) { v ->
                if (v == "pick") startActivity(Intent(this, WallpaperPickerActivity::class.java)) else prefs.wallpaper = v
            }
        }
        row("Aggiornamenti", "Versione ${BuildConfig.VERSION_CODE}") {
            // the check runs on the home screen, where its pop-up lives
            startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_CHECK_UPDATE))
            finish()
        }
        row("Notifiche", "Napoli · Ferrari · nuovi episodi") {
            val p = popup().title("Notifiche", "Banner sopra qualsiasi app: Napoli e Ferrari 10 minuti prima, nuovi episodi delle serie che segui.")
            p.button("Prova notifica") {
                // open Nello TV's home behind it so the banner shows over the usual screen
                finish()
                window.decorView.postDelayed({ WakeService.testBanner() }, 600)
            }
            p.showUnder(it)
        }
        row("Salvaschermo", if (prefs.screensaver) "Sfondo e orologio" else "Quello di Amazon") {
            prefs.screensaver = !prefs.screensaver
            build()
        }
        row("Prova salvaschermo", "›") {
            startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_SCREENSAVER))
            finish()
        }
        row("Modalità notte", if (prefs.night) "Attiva" else "Spenta") {
            prefs.night = !prefs.night
            build()
        }
        row("Dock", "${apps.get(apps.dock()).size}/${Apps.DOCK_MAX} app") { reorderFavourites(it) }
        row("Dock Sport", "${apps.get(apps.dock(true)).size}/${Apps.DOCK_MAX} app") { reorderFavourites(it, sport = true) }
        row("App nascoste", apps.hiddenApps().size.let { if (it == 0) "Nessuna" else "$it app" }) { hiddenApps(it) }
        row("Scheda iniziale", LauncherPrefs.TABS.first { it.first == prefs.startTab }.second) {
            choose(it, "Scheda iniziale", LauncherPrefs.TABS, prefs.startTab) { v -> prefs.startTab = v }
        }
        row("App VPN", LauncherPrefs.VPN_APPS.firstOrNull { it.first == prefs.vpnPackage }?.second ?: "Da scegliere") {
            choose(it, "App VPN", installedVpns(), prefs.vpnPackage) { v -> prefs.vpnPackage = v }
        }
        row("Account Nuvio", if (nuvio.isLinked) "Collegato · profilo ${nuvio.profileIndex}" else "Non collegato") { nuvioAccount(it) }

        header("Fire TV")
        system("Rete e Wi-Fi", Settings.ACTION_WIFI_SETTINGS)
        system("Telecomandi e dispositivi Bluetooth", "android.settings.CONTROLLERS_SETTINGS")
        system("Applicazioni", Settings.ACTION_APPLICATION_SETTINGS)
        system("La mia Fire TV · Opzioni sviluppatore", Settings.ACTION_DEVICE_INFO_SETTINGS)
        system("Accessibilità", Settings.ACTION_ACCESSIBILITY_SETTINGS)
        // Schermo e audio / Preferenze are reserved to Amazon's launcher: reachable from here
        system("Tutte le impostazioni Fire TV (anche Schermo e audio)", Settings.ACTION_SETTINGS)

        rows.getOrNull(focusRow)?.let { r -> r.post { r.requestFocus() } }
    }

    private fun system(title: String, action: String) = row(title, "›") {
        try {
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("Sezione non disponibile su questa Fire TV")
        }
    }

    // ---------------------------------------------------------------- pop-ups

    private fun choose(anchor: View, title: String, options: List<Pair<String, String>>, current: String?, set: (String) -> Unit) {
        if (options.isEmpty()) {
            toast("Nessuna app disponibile")
            return
        }
        val p = popup().title(title)
        options.forEach { (key, label) ->
            p.button(if (key == current) "✓  $label" else label) {
                set(key)
                build()
            }
        }
        p.showUnder(anchor)
    }

    private fun installedVpns() = LauncherPrefs.VPN_APPS.filter { (pkg, _) ->
        packageManager.getLeanbackLaunchIntentForPackage(pkg) != null || packageManager.getLaunchIntentForPackage(pkg) != null
    }

    /** Dock management: the 5 apps in order, each movable/removable, plus "Aggiungi app". */
    private fun reorderFavourites(anchor: View, sport: Boolean = false) {
        val favs = apps.get(apps.dock(sport))
        val p = popup().title(if (sport) "Dock Sport" else "Dock", "${favs.size}/${Apps.DOCK_MAX} app · scegli un'app da spostare o togliere")
        favs.forEachIndexed { i, app ->
            p.button("${i + 1}.  ${app.label}") { favouriteActions(anchor, app, sport) }
        }
        p.gap()
        p.button(if (apps.dockFull(sport)) "Sostituisci un'app…" else "＋  Aggiungi app") { pickAppForDock(anchor, sport) }
        p.showUnder(anchor)
    }

    private fun favouriteActions(anchor: View, app: AppInfo, sport: Boolean) {
        fun move(delta: Int) {
            apps.moveInDock(app.pkg, delta, sport)
            build()
            reorderFavourites(rows[focusRow], sport)
        }
        popup().title(app.label).apply {
            button("← Sposta a sinistra") { move(-1) }
            button("Sposta a destra →") { move(+1) }
            button("Rimuovi dal dock") {
                apps.removeFromDock(app.pkg, sport)
                build()
                reorderFavourites(rows[focusRow], sport)
            }
        }.showUnder(anchor)
    }

    private fun pickAppForDock(anchor: View, sport: Boolean) {
        val inDock = apps.dock(sport)
        val p = popup().title("Aggiungi al dock")
        apps.all().filter { it.pkg !in inDock && it.pkg != Apps.SETTINGS }.forEach { app ->
            p.button(app.label) {
                if (!apps.dockFull(sport)) {
                    apps.addToDock(app.pkg, sport = sport)
                    build()
                    reorderFavourites(rows[focusRow], sport)
                } else {
                    // full: choose which one it replaces
                    val r = popup().title("Dock pieno", "Al posto di quale app?")
                    apps.get(apps.dock(sport)).forEach { old ->
                        r.button(old.label) {
                            apps.addToDock(app.pkg, replacing = old.pkg, sport = sport)
                            build()
                            reorderFavourites(rows[focusRow], sport)
                        }
                    }
                    r.showUnder(anchor)
                }
            }
        }
        p.showUnder(anchor)
    }

    private fun hiddenApps(anchor: View) {
        val hidden = apps.hiddenApps()
        if (hidden.isEmpty()) {
            toast("Nessuna app nascosta · per nasconderne una tieni premuto OK sull'app")
            return
        }
        val p = popup().title("App nascoste", "Premi OK per mostrarla di nuovo")
        hidden.forEach { app ->
            p.button(app.label) {
                apps.hidden = apps.hidden - app.pkg
                build()
                if (apps.hiddenApps().isNotEmpty()) hiddenApps(rows[focusRow])
            }
        }
        p.showUnder(anchor)
    }

    private fun nuvioAccount(anchor: View) {
        if (!nuvio.isLinked) {
            // the QR flow lives on the home screen
            startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_LINK_NUVIO))
            finish()
            return
        }
        val p = popup().title("Account Nuvio", "Profilo letto per Continua a guardare e Libreria")
        (1..4).forEach { i ->
            val label = if (i == 1) "Profilo 1 (principale)" else "Profilo $i"
            p.button(if (i == nuvio.profileIndex) "✓  $label" else label) {
                nuvio.profileIndex = i
                build()
            }
        }
        p.gap()
        p.button("Scollega account") {
            nuvio.unlink()
            build()
        }
        p.showUnder(anchor)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
