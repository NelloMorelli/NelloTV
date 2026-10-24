package com.nello.tvhome

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

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

    /** WireGuard tunnel switched on before opening a sport event in Sportio (null = off) */
    var sportVpn: String?
        get() = if (p.contains("sportVpn")) p.getString("sportVpn", null) else "wgcf-profile"
        set(v) = p.edit().putString("sportVpn", v).apply()

    /** favourite football team (ESPN id and name); null = none */
    var teamId: String?
        get() = if (p.contains("teamId")) p.getString("teamId", null) else "114"
        set(v) = p.edit().putString("teamId", v).apply()
    var teamName: String?
        get() = if (p.contains("teamName")) p.getString("teamName", null) else "Napoli"
        set(v) = p.edit().putString("teamName", v).apply()
    /** F1 team followed in Per te and reminders; null = none */
    var f1Team: String?
        get() = if (p.contains("f1Team")) p.getString("f1Team", null) else "Ferrari"
        set(v) = p.edit().putString("f1Team", v).apply()
    /** Sportio's number for the playlist with the channels */
    var sportioSource: Int
        get() = p.getInt("sportioSource", 5)
        set(v) = p.edit().putInt("sportioSource", v).apply()
    /** where the first-start setup was left */
    var onboardingStep: Int
        get() = p.getInt("onboardingStep", 0)
        set(v) = p.edit().putInt("onboardingStep", v).apply()

    /** first-start setup done (an existing install counts as done) */
    var onboarded: Boolean
        get() = p.getBoolean("onboarded", p.all.isNotEmpty())
        set(v) = p.edit().putBoolean("onboarded", v).apply()

    /** Nello TV replaces Amazon's home (Home button included) */
    var replaceHome: Boolean
        get() = p.getBoolean("replaceHome", true)
        set(v) = p.edit().putBoolean("replaceHome", v).apply()

    var night: Boolean
        get() = p.getBoolean("night", false)
        set(v) = p.edit().putBoolean("night", v).apply()

    /** bumped every time a new local image is chosen, so the home screen reloads it */
    /** "Le tue immagini a rotazione": the folder (MediaStore relative path) and how often it changes */
    var rotationFolder: String?
        get() = p.getString("rotationFolder", null)
        set(v) = p.edit().putString("rotationFolder", v).apply()
    var rotationMinutes: Int
        get() = p.getInt("rotationMinutes", 60)
        set(v) = p.edit().putInt("rotationMinutes", v).apply()

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
            "bing" to "Foto del giorno (Bing)",
            "rotation" to "Le tue immagini a rotazione",
            "black" to "Nero",
            "custom" to "Immagine dal dispositivo",
        )
    }
}

/**
 * Nello TV's settings, full screen and on their own (not over the home): sections in a sidebar on
 * the left, their options on the right. ↑↓ in the sidebar switch section, → goes into the options,
 * ← or Back come back to the sidebar, Back from there closes.
 */
class SettingsActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(UiScale.wrap(newBase))


    private lateinit var prefs: LauncherPrefs
    private lateinit var apps: Apps
    private lateinit var nuvio: NuvioSync
    private lateinit var sidebar: LiquidList
    private lateinit var content: LiquidList
    private lateinit var scroll: PageScroller
    private val sectionViews = mutableListOf<View>()
    private val rows = mutableListOf<View>()
    private var section = 0
    /** which option to focus after a rebuild */
    private var focusRow = -1

    private val sections = listOf("Generale", "Aspetto", "Schermata", "Dock e app", "Sport e TV", "Account e backup", "Fire TV")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = LauncherPrefs(this)
        apps = Apps(this)
        nuvio = NuvioSync(this)
        Theme.load(this)

        // the wallpaper, blurred and dimmed: the same mood as the home, but a screen of its own
        val back = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            GlassView.blurred?.let { setImageBitmap(it) } ?: setImageResource(R.drawable.wallpaper)
        }
        val veil = View(this).apply { setBackgroundColor(0xA6000000.toInt()) }

        val title = TextView(this).apply {
            text = "Impostazioni"
            textSize = 30f
            typeface = Fonts.medium
            setTextColor(0xFFFFFFFF.toInt())
        }
        val clock = TextClock(this).apply {
            format12Hour = "HH:mm"; format24Hour = "HH:mm"
            textSize = 24f
            setTextColor(0xE6FFFFFF.toInt())
        }

        sidebar = LiquidList(this).apply { orientation = LinearLayout.VERTICAL; keepSelected = true }
        sections.forEachIndexed { i, name ->
            val v = TextView(this).apply {
                text = name
                textSize = 17f
                isFocusable = true
                isClickable = true
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), 0, dp(18), 0)
                setTextColor(0xE6FFFFFF.toInt())
                setOnFocusChangeListener { _, has -> if (has) selectSection(i) }
                setOnClickListener { focusContent() }
            }
            sidebar.addView(v, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(2) })
            sectionViews += v
        }

        content = LiquidList(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll = PageScroller(this).apply { addView(content) }
        val panel = LiquidCard(this).apply { addView(scroll, FrameLayout.LayoutParams(-1, -1)) }
        val indicator = ScrollIndicator(this, scroll)

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(back, FrameLayout.LayoutParams(-1, -1))
            addView(veil, FrameLayout.LayoutParams(-1, -1))
            addView(title, FrameLayout.LayoutParams(-2, -2).apply { setMargins(dp(62), dp(36), 0, 0) })
            addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.END).apply { setMargins(0, dp(40), dp(48), 0) })
            addView(sidebar, FrameLayout.LayoutParams(dp(250), -2).apply { setMargins(dp(44), dp(112), 0, 0) })
            addView(panel, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(320), dp(100), dp(48), dp(40)) })
            addView(indicator, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(320), dp(100), dp(48), dp(40)) })
        }
        setContentView(root)
        NavSound.attach(root)

        // a soft entrance: everything fades in, the two columns slide in a little
        root.alpha = 0f
        sidebar.translationX = -dp(24).toFloat()
        panel.translationX = dp(24).toFloat()
        val ease = android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f)
        root.post {
            root.animate().alpha(1f).setDuration(500).start()
            sidebar.animate().translationX(0f).setDuration(760).setInterpolator(ease).start()
            panel.animate().translationX(0f).setDuration(760).setInterpolator(ease).start()
        }
        build()
        // once laid out, so the pill knows where "Generale" is
        sectionViews[0].requestFocus()
        sidebar.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (sectionViews[0].height == 0) return
                sidebar.viewTreeObserver.removeOnGlobalLayoutListener(this)
                sidebar.pillTo(sectionViews[section])
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // back from the image picker: show the new choice
        if (rows.isNotEmpty()) build()
    }

    // ---------------------------------------------------------------- navigation

    private val switchSection = Runnable { build(animate = true) }

    /** false while build() parks the focus on the current section (no switching) */
    private var selecting = true

    private fun selectSection(i: Int) {
        if (!selecting) return
        if (i == section && content.childCount > 0) return
        section = i
        focusRow = -1
        // a short wait so running through the sidebar doesn't rebuild every section on the way
        content.removeCallbacks(switchSection)
        content.postDelayed(switchSection, 90)
    }

    private fun focusContent() {
        content.removeCallbacks(switchSection)
        if (content.childCount == 0) build()
        (rows.getOrNull(focusRow.coerceAtLeast(0)) ?: rows.firstOrNull())?.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val inContent = content.hasFocus()
        val inSidebar = sidebar.hasFocus()
        if (event.action == KeyEvent.ACTION_DOWN) when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (inSidebar) { focusContent(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (inContent) { sectionViews[section].requestFocus(); return true }
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && (inContent || inSidebar)) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (inContent) sectionViews[section].requestFocus()
                else { finish(); overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out) }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- building blocks

    private fun header(text: String) {
        if (content.childCount > 0) content.addView(View(this).apply { setBackgroundColor(0x1FFFFFFF) }, LinearLayout.LayoutParams(-1, dp(1)).apply {
            setMargins(dp(14), dp(12), dp(14), 0)
        })
        content.addView(TextView(this).apply {
            this.text = text.uppercase()
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(0x80FFFFFF.toInt())
            setPadding(dp(14), dp(10), 0, dp(4))
        })
    }

    private fun row(title: String, value: String?, onClick: (View) -> Unit) {
        val index = rows.size
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = null
            setPadding(dp(14), dp(11), dp(14), dp(11))
            setOnClickListener { focusRow = index; onClick(it) }
        }
        r.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(0xF2FFFFFF.toInt())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (value != null) r.addView(TextView(this).apply {
            text = value
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(0x99FFFFFF.toInt())
            setPadding(dp(12), 0, 0, 0)
        })
        content.addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(1) })
        rows += r
    }

    private fun note(text: String) = content.addView(TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(0x80FFFFFF.toInt())
        setPadding(dp(14), dp(2), dp(14), dp(8))
    })

    private fun popup() = GlassPopup(this)

    // ---------------------------------------------------------------- sections

    private fun build(animate: Boolean = false) {
        val hadFocus = content.hasFocus()
        // while the rows are rebuilt the focus would fall back to the sidebar and switch section:
        // park it on the current section's own entry first
        if (hadFocus) { selecting = false; sectionViews[section].requestFocus(); selecting = true }
        content.removeAllViews()
        rows.clear()
        Theme.load(this)
        when (section) {
            0 -> general()
            1 -> look()
            2 -> screen()
            3 -> dockAndApps()
            4 -> sportAndTv()
            5 -> account()
            6 -> fireTv()
        }
        if (hadFocus || focusRow >= 0) rows.getOrNull(focusRow.coerceAtLeast(0))?.let { r -> r.post { r.requestFocus() } }
        if (animate) {
            scroll.scrollTo(0, 0)
            content.alpha = 0f
            content.translationY = dp(10).toFloat()
            content.animate().alpha(1f).translationY(0f).setDuration(420)
                .setInterpolator(android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f)).start()
        }
    }

    private fun general() {
        header("Sfondo e schermo")
        row("Sfondo", LauncherPrefs.WALLPAPERS.first { it.first == prefs.wallpaper }.second) {
            val hasCustom = java.io.File(filesDir, WallpaperPickerActivity.CUSTOM_FILE).exists()
            val options = LauncherPrefs.WALLPAPERS.filter { (k, _) -> k != "custom" && (k != "rotation" || prefs.rotationFolder != null) } +
                (if (hasCustom) listOf("custom" to "La tua immagine") else emptyList()) +
                ("pick" to "Scegli immagine o cartella dal dispositivo…")
            choose(it, "Sfondo", options, prefs.wallpaper) { v ->
                if (v == "pick") openPage(Intent(this, WallpaperPickerActivity::class.java)) else prefs.wallpaper = v
            }
        }
        if (prefs.wallpaper == "rotation") {
            val mins = listOf(15 to "15 minuti", 30 to "30 minuti", 60 to "1 ora", 180 to "3 ore", 1440 to "Ogni giorno")
            row("Cambia sfondo ogni", mins.firstOrNull { it.first == prefs.rotationMinutes }?.second ?: "${prefs.rotationMinutes} min") { v ->
                pick(v, "Cambia sfondo ogni", mins.map { it.second }, mins.indexOfFirst { it.first == prefs.rotationMinutes }) { i -> prefs.rotationMinutes = mins[i].first }
            }
            row("Cartella", prefs.rotationFolder?.trimEnd('/')?.ifEmpty { "Memoria principale" } ?: "—") { openPage(Intent(this, WallpaperPickerActivity::class.java)) }
        }
        row("Modalità notte", if (prefs.night) "Attiva" else "Spenta") {
            prefs.night = !prefs.night
            build()
        }
        row("Suoni di navigazione", if (NavSound.enabled) "Attivi" else "Spenti") {
            NavSound.enabled = !NavSound.enabled
            Theme.edit(this).putBoolean("navSounds", NavSound.enabled).apply()
            build()
        }
        header("Salvaschermo")
        row("Salvaschermo", if (prefs.screensaver) "Sfondo e orologio" else "Quello di Amazon") {
            prefs.screensaver = !prefs.screensaver
            build()
        }
        val waits = listOf(2, 5, 10, 15, 30)
        val wait = runCatching { Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) / 60_000 }.getOrDefault(5)
        row("Parte dopo", "$wait minuti") { v ->
            pick(v, "Salvaschermo dopo", waits.map { "$it minuti" }, waits.indexOf(wait)) { i ->
                runCatching { Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, waits[i] * 60_000) }
                    .onFailure { toast("Permesso mancante per cambiare il tempo") }
            }
        }
        row("Widget del salvaschermo", listOf("match" to "Partita", "f1" to "F1", "news" to "Notizie").filter { ScreensaverView.enabled(this, it.first) }.joinToString(" · ") { it.second }.ifEmpty { "Nessuno" }) { v ->
            val p = popup().title("Widget del salvaschermo", "In basso, sotto l'orologio")
            listOf("match" to "Prossima partita della tua squadra", "f1" to "Prossima sessione di F1", "news" to "Notizie").forEach { (k, n) ->
                p.button(if (ScreensaverView.enabled(this, k)) "✓  $n" else n) {
                    Theme.edit(this).putBoolean("ss_$k", !ScreensaverView.enabled(this, k)).apply(); build()
                }
            }
            p.showUnder(v)
        }
        row("Fonti delle notizie", News.chosen(this).size.let { "$it fonti" }) { v -> newsFeeds(v) }
        row("Prova salvaschermo", "›") {
            startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_SCREENSAVER))
            finish()
        }
        header("Avvisi e aggiornamenti")
        row("Notifiche", "Napoli · Ferrari · nuovi episodi") {
            val p = popup().title("Notifiche", "Banner sopra qualsiasi app: Napoli e Ferrari 10 minuti prima, nuovi episodi delle serie che segui.")
            p.button("Prova notifica") {
                finish()
                window.decorView.postDelayed({ WakeService.testBanner() }, 600)
            }
            p.showUnder(it)
        }
        row("Configurazione iniziale", "Permessi, squadra, canali ›") {
            // until it's finished again, Home brings it back
            prefs.onboarded = false
            prefs.onboardingStep = 0
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        row("Aggiornamenti", "Versione ${Updater.name()}") {
            // the check runs on the home screen, where its pop-up lives
            startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_CHECK_UPDATE))
            finish()
        }
    }

    private fun look() {
        header("Dimensioni")
        row("Dimensione interfaccia", UiScale.OPTIONS.firstOrNull { it.first == UiScale.factor(this) }?.second ?: "${(UiScale.factor(this) * 100).toInt()}%") { v ->
            pick(v, "Dimensione interfaccia", UiScale.OPTIONS.map { it.second }, UiScale.OPTIONS.indexOfFirst { it.first == UiScale.factor(this) }) { i ->
                Theme.edit(this).putFloat("uiScale", UiScale.OPTIONS[i].first).apply()
                // this screen too, right away
                recreate()
            }
        }
        header("Colori e vetro")
        row("Colore principale", Theme.ACCENTS.firstOrNull { it.second == Theme.accent }?.first ?: "Personalizzato") { v ->
            pick(v, "Colore principale", Theme.ACCENTS.map { it.first }, Theme.ACCENTS.indexOfFirst { it.second == Theme.accent }) { i ->
                Theme.edit(this).putInt("accent", Theme.ACCENTS[i].second).apply()
            }
        }
        val glass = listOf("Leggero", "Normale", "Intenso")
        row("Effetto vetro", glass[Theme.glass]) { v -> pick(v, "Effetto vetro", glass, Theme.glass) { Theme.edit(this).putInt("glass", it).apply() } }
        val dims = listOf(0f, 0.15f, 0.3f, 0.45f, 0.6f)
        val dimNames = listOf("Nessuno", "Leggero", "Medio", "Forte", "Molto forte")
        row("Oscura lo sfondo", dimNames[dims.indexOfFirst { it >= Theme.dim - 0.01f }.coerceAtLeast(0)]) { v ->
            pick(v, "Oscura lo sfondo", dimNames, dims.indexOfFirst { it >= Theme.dim - 0.01f }) { Theme.edit(this).putFloat("dim", dims[it]).apply() }
        }
        header("Forme")
        val rounds = listOf(0.4f, 1f, 1.6f)
        val roundNames = listOf("Squadrati", "Normali", "Molto arrotondati")
        row("Angoli", roundNames[rounds.indexOf(Theme.roundness).coerceAtLeast(0)]) { v ->
            pick(v, "Angoli", roundNames, rounds.indexOf(Theme.roundness)) { Theme.edit(this).putFloat("roundness", rounds[it]).apply() }
        }
        val sizes = listOf(0.85f, 1f, 1.12f)
        val sizeNames = listOf("Compatte", "Normali", "Grandi")
        row("Dimensione card", sizeNames[sizes.indexOf(Theme.cardScale).coerceAtLeast(0)]) { v ->
            pick(v, "Dimensione card", sizeNames, sizes.indexOf(Theme.cardScale)) { Theme.edit(this).putFloat("cardScale", sizes[it]).apply() }
        }
        header("Movimento")
        val speeds = listOf(0f, 0.35f, 0.5f, 0.8f)
        val speedNames = listOf("Disattivate", "Veloci", "Normali", "Lente")
        val cur = runCatching { Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE) }.getOrDefault(0.5f)
        row("Animazioni", speedNames[speeds.indexOfFirst { Math.abs(it - cur) < 0.05f }.coerceAtLeast(2)]) { v ->
            pick(v, "Animazioni", speedNames, speeds.indexOfFirst { Math.abs(it - cur) < 0.05f }) { i ->
                runCatching {
                    listOf(Settings.Global.ANIMATOR_DURATION_SCALE, Settings.Global.TRANSITION_ANIMATION_SCALE, Settings.Global.WINDOW_ANIMATION_SCALE)
                        .forEach { k -> Settings.Global.putFloat(contentResolver, k, speeds[i]) }
                }.onFailure { toast("Permesso mancante per cambiare le animazioni") }
            }
        }
        header("")
        row("Ripristina l'aspetto", "›") {
            val e = Theme.edit(this)
            listOf("accent", "glass", "dim", "roundness", "cardScale").forEach { k -> e.remove(k) }
            e.apply()
            toast("Aspetto ripristinato")
            build()
        }
    }

    private fun screen() {
        header("All'avvio")
        row("Scheda iniziale", LauncherPrefs.TABS.first { it.first == prefs.startTab }.second) {
            choose(it, "Scheda iniziale", LauncherPrefs.TABS, prefs.startTab) { v -> prefs.startTab = v }
        }
        header("Sullo schermo")
        toggle("Dock", "showDock", Theme.showDock)
        toggle("Titolo e info sotto le card", "showCardInfo", Theme.showCardInfo)
        header("Schede")
        val hiddenT = Customize.hiddenTabs(this)
        Customize.TABS.forEach { (key, name) ->
            row(name, if (key in hiddenT) "Nascosta" else "Visibile") {
                Theme.edit(this).putStringSet("hiddenTabs", if (key in hiddenT) hiddenT - key else hiddenT + key).apply()
                build()
            }
        }
        header("Cataloghi")
        val hiddenC = Customize.hiddenCatalogs(this)
        Customize.CATALOGS.forEach { (tab, cats) ->
            val off = cats.count { it.first in hiddenC }
            row(tab, if (off == 0) "Tutti visibili" else "$off nascosti") { v -> catalogs(v, tab, cats) }
        }
    }

    private fun dockAndApps() {
        header("Dock")
        row("Dock", "${apps.get(apps.dock()).size}/${Apps.DOCK_MAX} app") { reorderFavourites(it) }
        row("Dock Sport", "${apps.get(apps.dock(true)).size}/${Apps.DOCK_MAX} app") { reorderFavourites(it, sport = true) }
        header("Righe e cartelle")
        val col = Collections(this)
        row("Le tue righe", col.rows.size.let { if (it == 0) "Nessuna" else "$it in Esplora" }) { v -> customRows(v) }
        row("Cartelle", col.folders.size.let { if (it == 0) "Nessuna" else "$it" }) { v -> folders(v) }
        note("Per mettere un'app in una riga o in una cartella: tieni premuto OK sull'app nella home.")
        header("App")
        row("App nascoste", apps.hiddenApps().size.let { if (it == 0) "Nessuna" else "$it app" }) { hiddenApps(it) }
        row("Rinomina un'app", "›") { v -> renamePick(v) }
        val names = getSharedPreferences("app_names", MODE_PRIVATE).all
        if (names.isNotEmpty()) row("Ripristina i nomi originali", "${names.size} app") {
            getSharedPreferences("app_names", MODE_PRIVATE).edit().clear().apply()
            toast("Nomi originali ripristinati")
            build()
        }
    }

    private fun sportAndTv() {
        header("Le tue squadre")
        row("Squadra del cuore", prefs.teamName ?: "Nessuna") { v ->
            lifecycleScope.launch {
                val teams = runCatching { Espn.serieATeams() }.getOrElse { emptyList() }
                choose(v, "Squadra del cuore", listOf("" to "Nessuna") + teams, prefs.teamId ?: "") { id ->
                    prefs.teamId = id.ifEmpty { null }
                    prefs.teamName = teams.firstOrNull { it.first == id }?.second
                }
            }
        }
        val f1 = listOf("Ferrari", "McLaren", "Red Bull", "Mercedes", "Aston Martin", "Williams", "Alpine", "Racing Bulls", "Haas", "Audi", "Cadillac")
        row("Scuderia F1", prefs.f1Team ?: "Nessuna") { v ->
            choose(v, "Scuderia F1", listOf("" to "Nessuna") + f1.map { it to it }, prefs.f1Team ?: "") { prefs.f1Team = it.ifEmpty { null } }
        }
        header("Sportio")
        row("Playlist Sportio", "Numero ${prefs.sportioSource}") { v ->
            pick(v, "Playlist Sportio", (1..10).map { "Playlist $it" }, prefs.sportioSource - 1) { i -> prefs.sportioSource = i + 1 }
        }
        row("Prova: apri RAI 1", "›") { Sportio.play(this, "RAI 1") }
        note("Se Sportio apre la lista dei canali invece di RAI 1, scegli un altro numero di playlist.")
        header("Sport e canali")
        row("WireGuard per lo sport", prefs.sportVpn?.let { "Attiva · $it" } ?: "Spenta") {
            prefs.sportVpn = if (prefs.sportVpn == null) "wgcf-profile" else null
            build()
        }
        note("Si attiva quando apri una partita o un canale Sky in Sportio e si spegne quando torni alla home.")
        header("VPN")
        row("App VPN", LauncherPrefs.VPN_APPS.firstOrNull { it.first == prefs.vpnPackage }?.second ?: "Da scegliere") {
            choose(it, "App VPN", installedVpns(), prefs.vpnPackage) { v -> prefs.vpnPackage = v }
        }
    }

    private fun account() {
        header("Backup")
        row("Salva le impostazioni", "In Download ›") {
            lifecycleScope.launch {
                val r = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Backup.export(this@SettingsActivity) } }
                r.onSuccess { toast("Salvato in Download: $it") }.onFailure { toast("Backup non riuscito: ${it.message}") }
                build()
            }
        }
        val found = runCatching { Backup.list(this) }.getOrElse { emptyList() }
        row("Ripristina impostazioni", if (found.isEmpty()) "Nessun backup in Download" else "${found.size} backup ›") { v ->
            if (found.isEmpty()) { toast("Copia un file NelloTV-backup in Download (ad esempio con LocalSend)"); return@row }
            val p = popup().title("Ripristina", "Sostituisce le impostazioni attuali")
            found.forEach { (name, uri) ->
                p.button(name.removePrefix("NelloTV-backup-").removeSuffix(".json")) {
                    val ok = runCatching { Backup.restore(this, uri) }.getOrDefault(false)
                    if (!ok) { toast("Questo file non è un backup di Nello TV"); return@button }
                    toast("Impostazioni ripristinate")
                    // start fresh with everything restored
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    finish()
                    Runtime.getRuntime().exit(0)
                }
            }
            p.showUnder(v)
        }
        note("Il file contiene dock, righe, cartelle, aspetto, squadra, playlist Sportio, account Nuvio e il tuo sfondo. Invialo all'altra Fire TV con LocalSend e ripristinalo da qui.")
        header("Nuvio")
        row("Account Nuvio", if (nuvio.isLinked) "Collegato · profilo ${nuvio.profileIndex}" else "Non collegato") { nuvioAccount(it) }
        note("Serve per Continua a guardare e per i consigli in Per te.")
    }

    private fun fireTv() {
        header("Home")
        row("Nello TV come home", if (prefs.replaceHome) "Sì · anche il tasto Home" else "Solo dopo lo standby") {
            prefs.replaceHome = !prefs.replaceHome
            build()
        }
        row("Apri la home di Amazon", "›") {
            WakeService.amazonHomeUntil = System.currentTimeMillis() + 60_000
            runCatching {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setPackage("com.amazon.tv.launcher").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        header("Impostazioni della Fire TV")
        system("Rete e Wi-Fi", Settings.ACTION_WIFI_SETTINGS)
        system("Telecomandi e dispositivi Bluetooth", "android.settings.CONTROLLERS_SETTINGS")
        system("Applicazioni", Settings.ACTION_APPLICATION_SETTINGS)
        system("La mia Fire TV · Opzioni sviluppatore", Settings.ACTION_DEVICE_INFO_SETTINGS)
        system("Accessibilità", Settings.ACTION_ACCESSIBILITY_SETTINGS)
        // Schermo e audio / Preferenze are reserved to Amazon's launcher: reachable from here
        system("Tutte le impostazioni (anche Schermo e audio)", Settings.ACTION_SETTINGS)
    }

    private fun system(title: String, action: String) = row(title, "›") {
        try {
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("Sezione non disponibile su questa Fire TV")
        }
    }

    private fun toggle(title: String, key: String, on: Boolean) = row(title, if (on) "Sì" else "No") {
        Theme.edit(this).putBoolean(key, !on).apply()
        build()
    }

    // ---------------------------------------------------------------- pop-ups

    private fun pick(anchor: View, title: String, options: List<String>, current: Int, set: (Int) -> Unit) {
        val p = popup().title(title)
        options.forEachIndexed { i, o ->
            p.button(if (i == current) "✓  $o" else o) {
                set(i)
                build()
            }
        }
        p.showUnder(anchor)
    }

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

    private fun catalogs(anchor: View, tab: String, cats: List<Pair<String, String>>) {
        val hidden = Customize.hiddenCatalogs(this)
        val p = popup().title(tab, "OK per mostrare o nascondere")
        cats.forEach { (key, name) ->
            p.button(if (key in hidden) name else "✓  $name") {
                val next = if (key in hidden) hidden - key else hidden + key
                // keep at least one catalogue per tab
                if (cats.all { it.first in next }) toast("Almeno un catalogo deve restare") else Theme.edit(this).putStringSet("hiddenCatalogs", next).apply()
                build()
                rows.getOrNull(focusRow)?.let { r -> r.post { catalogs(r, tab, cats) } }
            }
        }
        p.showUnder(anchor)
    }

    // ---------------------------------------------------------------- rows & folders

    private fun customRows(anchor: View) {
        val col = Collections(this)
        val p = popup().title("Le tue righe", "Compaiono in Esplora, sotto Continua a guardare")
        col.rows.forEach { r -> p.button("${r.name}  ·  ${r.apps.size} app") { rowActions(anchor, r.id) } }
        p.gap()
        p.button("＋  Nuova riga") {
            askText(anchor, "Nome della riga", "") { name -> val r = col.createRow(name); build(); rowApps(rows[focusRow], r.id) }
        }
        p.showUnder(anchor)
    }

    private fun rowActions(anchor: View, id: String) {
        val col = Collections(this)
        val r = col.rows.firstOrNull { it.id == id } ?: return
        val i = col.rows.indexOf(r)
        val p = popup().title(r.name)
        p.button("App nella riga…") { rowApps(anchor, id) }
        p.button("Rinomina") { askText(anchor, "Nome della riga", r.name) { col.renameRow(id, it); build() } }
        if (i > 0) p.button("↑ Sposta su") { col.moveRow(id, -1); build(); customRows(rows[focusRow]) }
        if (i < col.rows.size - 1) p.button("↓ Sposta giù") { col.moveRow(id, +1); build(); customRows(rows[focusRow]) }
        p.button("Elimina riga") { col.deleteRow(id); build() }
        p.showUnder(anchor)
    }

    private fun rowApps(anchor: View, id: String) {
        val col = Collections(this)
        val r = col.rows.firstOrNull { it.id == id } ?: return
        val p = popup().title(r.name, "OK per aggiungere o togliere un'app")
        apps.all().filter { it.pkg != Apps.SETTINGS }.forEach { a ->
            p.button(if (a.pkg in r.apps) "✓  ${a.label}" else a.label) {
                col.toggleInRow(id, a.pkg)
                build()
                rowApps(rows[focusRow], id)
            }
        }
        p.showUnder(anchor)
    }

    private fun folders(anchor: View) {
        val col = Collections(this)
        if (col.folders.isEmpty()) { toast("Nessuna cartella · tieni premuto OK su un'app nella home e scegli \"Metti in una cartella\""); return }
        val p = popup().title("Cartelle")
        col.folders.forEach { f ->
            p.button("${f.name}  ·  ${f.apps.size} app") {
                val q = popup().title(f.name)
                q.button("Rinomina") { askText(anchor, "Nome della cartella", f.name) { col.renameFolder(f.id, it); build() } }
                apps.get(f.apps).forEach { a -> q.button("Togli ${a.label}") { col.removeFromFolder(a.pkg); build() } }
                q.button("Elimina cartella") { col.deleteFolder(f.id); build() }
                q.showUnder(anchor)
            }
        }
        p.showUnder(anchor)
    }

    private fun askText(anchor: View, title: String, initial: String, done: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelection(initial.length)
            hint = "Scrivi un nome"
            setHintTextColor(0x80FFFFFF.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setSingleLine()
        }
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val p = popup().title(title).view(input)
        p.onDismiss = { imm.hideSoftInputFromWindow(window.decorView.windowToken, 0) }
        fun save() {
            val v = input.text.toString().trim()
            if (v.isEmpty()) return
            imm.hideSoftInputFromWindow(input.windowToken, 0)
            p.dismiss()
            done(v)
        }
        input.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { save(); true } else false }
        p.button("Salva", keepOpen = true) { save() }
        p.initialFocus = { input }
        p.showUnder(anchor)
        input.postDelayed({ imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_FORCED) }, 250)
    }

    private fun newsFeeds(anchor: View) {
        val chosen = News.chosen(this)
        val p = popup().title("Fonti delle notizie", "OK per attivare o disattivare")
        News.PRESETS.forEach { (name, _) ->
            p.button(if (name in chosen) "✓  $name" else name) {
                News.setChosen(this, if (name in chosen) chosen - name else chosen + name)
                build(); newsFeeds(rows[focusRow])
            }
        }
        chosen.filter { c -> News.PRESETS.none { it.first == c } }.forEach { url ->
            p.button("✓  ${url.substringAfter("://").substringBefore('/')}") { News.setChosen(this, chosen - url); build(); newsFeeds(rows[focusRow]) }
        }
        p.gap()
        p.button("＋  Aggiungi un feed RSS…") {
            askText(anchor, "Indirizzo del feed (RSS o Atom)", "https://") { url ->
                if (!url.startsWith("http")) { toast("Indirizzo non valido"); return@askText }
                lifecycleScope.launch {
                    val ok = runCatching { News.parse(Net.get(url), "").isNotEmpty() }.getOrDefault(false)
                    if (ok) { News.setChosen(this@SettingsActivity, News.chosen(this@SettingsActivity) + url); toast("Feed aggiunto"); build() }
                    else toast("Nessuna notizia trovata a quell'indirizzo")
                }
            }
        }
        p.showUnder(anchor)
    }

    private fun renamePick(anchor: View) {
        val p = popup().title("Rinomina un'app")
        apps.all().filter { it.pkg != Apps.SETTINGS }.forEach { a -> p.button(a.label) { rename(anchor, a) } }
        p.showUnder(anchor)
    }

    private fun rename(anchor: View, app: AppInfo) {
        val input = EditText(this).apply {
            setText(app.label)
            selectAll()
            setTextColor(0xFFFFFFFF.toInt())
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setSingleLine()
        }
        val p = popup().title("Nuovo nome", app.label).view(input)
        fun save() {
            val n = input.text.toString().trim()
            if (n.isNotEmpty()) getSharedPreferences("app_names", MODE_PRIVATE).edit().putString(app.pkg, n).apply()
            toast("Rinominata in \"$n\"")
            p.dismiss()
            build()
        }
        input.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { save(); true } else false }
        p.button("Salva", keepOpen = true) { save() }
        p.showUnder(anchor)
        input.post { input.requestFocus() }
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
