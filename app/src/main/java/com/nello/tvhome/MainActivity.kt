package com.nello.tvhome

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity(), Host {

    companion object {
        const val ACTION_LINK_NUVIO = "com.nello.tvhome.LINK_NUVIO"
        const val ACTION_OPEN_SPORT = "com.nello.tvhome.OPEN_SPORT"
        const val ACTION_CHECK_UPDATE = "com.nello.tvhome.CHECK_UPDATE"
    }

    /** Order matches the tabs in the top bar. */
    private enum class Section { SEARCH, EXPLORE, MOVIES, SERIES, SPORT }

    /** A row definition: a synchronous app list, or a suspend loader for media or matches. */
    private class Spec(
        val key: String,
        val title: String,
        val kind: RowKind,
        val apps: (() -> List<AppInfo>)? = null,
        val media: (suspend () -> List<MediaItem>)? = null,
        val matches: (suspend () -> List<Match>)? = null,
        val mixed: (suspend () -> List<Any>)? = null,
    )

    private inner class SectionState(val section: Section, val specs: List<Spec>) {
        val rows = specs.map { Row(it.key, it.title, it.kind) }
        val ready = BooleanArray(specs.size)
        var loadedAt = 0L
        /** Rows in order, up to the first one still loading: rows are only ever added below, nothing jumps. */
        fun visible(): List<Row> {
            val firstPending = ready.indexOfFirst { !it }.let { if (it < 0) rows.size else it }
            return rows.take(firstPending).filter { it.size > 0 }
        }
    }

    private lateinit var apps: Apps
    private lateinit var nuvio: NuvioSync
    private lateinit var prefs: LauncherPrefs
    private lateinit var network: NetworkMonitor
    private var wasOffline = false
    private var appliedWallpaper: String? = null
    private lateinit var adapter: RowsAdapter

    private lateinit var rowsView: RecyclerView
    private lateinit var greeting: View
    private lateinit var info: View
    private lateinit var infoLogo: ImageView
    private lateinit var infoTitle: TextView
    private lateinit var infoMeta: TextView
    private lateinit var infoOverview: TextView
    private lateinit var dateView: TextView
    private lateinit var helloView: TextView
    private lateinit var searchBox: View
    private lateinit var searchInput: EditText
    private lateinit var tabs: List<TextView>
    private lateinit var single: View
    private lateinit var hero: View
    private lateinit var carousel: RowList
    private lateinit var carouselTitle: TextView
    private lateinit var carouselMeta: TextView
    private lateinit var catalogLabel: TextView
    private lateinit var dockList: RowList

    /** which catalog each single-screen tab shows (index in its rows) */
    private val catalogIndex = HashMap<Section, Int>()
    private var shownCatalog: Row? = null
    private var shownItems: List<Any> = emptyList()
    private var carouselAdapter: CarouselAdapter? = null
    private var dockApps: List<String> = emptyList()

    /** the Sport tab has its own dock (sport apps) */
    private val sportDock get() = current == Section.SPORT
    private lateinit var page: PageScroll
    private lateinit var below: View
    private lateinit var continueList: RowList
    private lateinit var bento: android.widget.LinearLayout
    private var shownContinue: List<MediaItem>? = null
    private var shownBento: List<String>? = null

    private fun isSingle(section: Section) = section != Section.SEARCH

    /** tabs with extra rows below the dock (scrolling page) */
    private fun hasBelow(section: Section) = section == Section.EXPLORE || section == Section.SPORT

    private val states = HashMap<Section, SectionState>()
    private var current = Section.EXPLORE
    private var heroJob: Job? = null
    private var tabJob: Job? = null
    private var searchJob: Job? = null
    private var searchRows: List<Row> = emptyList()
    /** false until the user presses a key after a section opens: rows loading at the top may take the focus */
    private var userMoved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // the background bitmap covers the whole screen: no window background to paint underneath
        window.setBackgroundDrawable(null)
        apps = Apps(this)
        SportArt.init(this)
        nuvio = NuvioSync(this)
        prefs = LauncherPrefs(this)
        network = NetworkMonitor(this, ::onNetworkChanged)

        rowsView = findViewById(R.id.rows)
        greeting = findViewById(R.id.greeting)
        info = findViewById(R.id.info)
        infoLogo = findViewById(R.id.infoLogo)
        infoTitle = findViewById(R.id.infoTitle)
        infoMeta = findViewById(R.id.infoMeta)
        infoOverview = findViewById(R.id.infoOverview)
        dateView = findViewById(R.id.date)
        helloView = findViewById(R.id.hello)
        searchBox = findViewById(R.id.searchBox)
        searchInput = findViewById(R.id.searchInput)
        tabs = listOf(
            findViewById(R.id.tabSearch), findViewById(R.id.tabExplore), findViewById(R.id.tabMovies),
            findViewById(R.id.tabSeries), findViewById(R.id.tabSport),
        )

        page = findViewById(R.id.page)
        below = findViewById(R.id.below)
        continueList = findViewById(R.id.continueList)
        bento = findViewById(R.id.bento)
        // the first screen (header, carousel, dock) is exactly one screen tall; Esplora's extra rows sit below
        findViewById<View>(R.id.main).layoutParams.height = resources.displayMetrics.heightPixels
        continueList.layoutManager = StartAlignedLayoutManager(this, RecyclerView.HORIZONTAL)
        continueList.itemAnimator = null
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, new -> followFocus(new) }
        single = findViewById(R.id.single)
        hero = findViewById(R.id.hero)
        carousel = findViewById(R.id.carousel)
        carouselTitle = findViewById(R.id.carouselTitle)
        carouselMeta = findViewById(R.id.carouselMeta)
        catalogLabel = findViewById(R.id.catalogLabel)
        dockList = findViewById(R.id.dockList)
        carousel.layoutManager = StartAlignedLayoutManager(this, RecyclerView.HORIZONTAL)
        // room after the last card so even it can slide to the start and open fully on screen
        val wide = (CarouselAdapter.WIDE_W * resources.displayMetrics.density).toInt()
        carousel.setPadding(carousel.paddingLeft, carousel.paddingTop,
            resources.displayMetrics.widthPixels - wide - carousel.paddingLeft, carousel.paddingBottom)
        carousel.itemAnimator = null
        carousel.wrapAround = true
        // coming back from the dock or the tabs lands on the wide card
        carousel.preferred = { carouselAdapter?.let { carousel.layoutManager?.findViewByPosition(it.active) } }
        dockList.layoutManager = StartAlignedLayoutManager(this, RecyclerView.HORIZONTAL)
        // entering the dock lands on its first app on screen, not under the wide card
        dockList.preferred = {
            (0 until dockList.childCount).map { dockList.getChildAt(it) }
                .filter { it.left >= dockList.paddingLeft - 4 }.minByOrNull { it.left }
        }
        dockList.itemAnimator = null
        catalogLabel.setOnClickListener { if (current == Section.EXPLORE) refreshPerTe() else showCatalogPicker() }
        refreshDock()

        adapter = RowsAdapter(this)
        rowsView.layoutManager = StartAlignedLayoutManager(this, RecyclerView.VERTICAL)
        rowsView.itemAnimator = null
        rowsView.adapter = adapter

        setupTabs()
        setupSearch()
        setupTopBar()
        applyPrefs()
        tickClock()
        tickLiveScores()

        onBackPressedDispatcher.addCallback(this) { goTop() }
        // back where you were if the launcher was closed while you were in an app
        val resume = getSharedPreferences("where", MODE_PRIVATE)
        val recent = System.currentTimeMillis() - resume.getLong("at", 0) < 6 * 3600_000L
        val section = if (recent) runCatching { Section.valueOf(resume.getString("section", "")!!) }.getOrNull() else null
        if (section != null && isSingle(section)) pendingCard[section] = resume.getInt("card", 0)
        show(section ?: startSection(), focusContent = true)
        preloadSections()
        checkForUpdate()
    }

    /**
     * Fire OS turns accessibility services off when an app update changes its permissions:
     * switch ours (standby return, notifications) back on. Needs WRITE_SECURE_SETTINGS, granted via adb.
     */
    private fun ensureBackgroundService() {
        val me = "$packageName/${WakeService::class.java.name}"
        runCatching {
            val cur = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            if (cur.split(':').any { it == me || it == "$packageName/.WakeService" }) return
            val next = if (cur.isBlank()) me else "$cur:$me"
            android.provider.Settings.Secure.putString(contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
            android.provider.Settings.Secure.putInt(contentResolver, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }
    }

    /** New version on GitHub: offer it once per version (also on every launcher start). */
    private fun checkForUpdate(manual: Boolean = false) {
        lifecycleScope.launch {
            delay(if (manual) 0 else 8_000)
            val r = runCatching { Updater.check() }.getOrNull()
            if (r == null) {
                if (manual) toast(if (Updater.configured) "Nello TV è aggiornato (versione ${BuildConfig.VERSION_CODE})" else "Aggiornamenti non configurati")
                return@launch
            }
            val seen = getSharedPreferences("update", MODE_PRIVATE)
            if (!manual && seen.getInt("offered", 0) == r.version) return@launch
            seen.edit().putInt("offered", r.version).apply()
            val p = GlassPopup(this@MainActivity, widthDp = 420)
                .title("Aggiornamento disponibile", "Versione ${r.version} · ora hai la ${BuildConfig.VERSION_CODE}")
            if (r.notes.isNotBlank()) p.text(r.notes.take(400))
            p.button("Installa ora") {
                toast("Scarico l'aggiornamento…")
                lifecycleScope.launch {
                    runCatching { Updater.install(this@MainActivity, r) }.onFailure { toast("Download non riuscito: ${it.message}") }
                }
            }
            p.button("Più tardi") {}
            p.showCentered(rowsView)
        }
    }

    fun checkForUpdateNow() = checkForUpdate(manual = true)

    /** Load the other tabs in the background, so switching tab shows them at once. */
    private fun preloadSections() {
        lifecycleScope.launch {
            delay(2_500)
            for (section in listOf(Section.MOVIES, Section.SERIES, Section.SPORT)) {
                if (states[section] == null) SectionState(section, specs(section)).also { states[section] = it; load(it) }
            }
        }
    }

    private fun startSection() = runCatching { Section.valueOf(prefs.startTab) }.getOrDefault(Section.EXPLORE)

    override fun onStart() {
        super.onStart()
        network.start()
    }

    override fun onStop() {
        super.onStop()
        network.stop()
    }

    /** Remote shortcuts: ⏪/⏩ change tab, ☰ opens settings (on app cards ☰ keeps its app menu). */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (currentFocus is EditText) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_MEDIA_REWIND, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                switchTab(-1); return true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> {
                switchTab(+1); return true
            }
            android.view.KeyEvent.KEYCODE_MENU -> {
                if (isSingle(current) && current != Section.EXPLORE && carousel.hasFocus()) showCatalogPicker() else showSettingsPopup()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun switchTab(delta: Int) {
        val next = Section.entries[(current.ordinal + delta + Section.entries.size) % Section.entries.size]
        show(next, focusContent = next != Section.SEARCH)
        if (next == Section.SEARCH) searchInput.requestFocus()
    }

    // ---------------------------------------------------------------- top bar

    private fun setupTopBar() {
        findViewById<FocusGroup>(R.id.barGroup).showSelected = false
        findViewById<GlassView>(R.id.rightBar).apply { zoomOnFocus = false; frosted = true }
        findViewById<GlassView>(R.id.dock).frosted = true
        findViewById<GlassView>(R.id.searchGlass).apply { frosted = true; zoomOnFocus = false }
        findViewById<StatusIcon>(R.id.gearIcon).kind = StatusIcon.Kind.GEAR
        findViewById<StatusIcon>(R.id.vpnIcon).kind = StatusIcon.Kind.VPN
        findViewById<View>(R.id.settingsButton).setOnClickListener { showSettingsPopup() }
        findViewById<View>(R.id.netButton).setOnClickListener { showNetworkPopup() }
        findViewById<View>(R.id.vpnButton).setOnClickListener { showVpnPopup() }
        onNetworkChanged(network.state)
        // Wi-Fi signal strength doesn't always trigger a callback
        lifecycleScope.launch {
            while (isActive) {
                delay(15_000)
                network.refresh()
            }
        }
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun openConnectionDetails() = startActivity(Intent(this, ConnectionActivity::class.java))

    private fun onNetworkChanged(s: NetworkMonitor.State) {
        findViewById<StatusIcon>(R.id.netIcon).apply {
            kind = when {
                !s.online -> StatusIcon.Kind.OFFLINE
                s.ethernet -> StatusIcon.Kind.ETHERNET
                else -> StatusIcon.Kind.WIFI
            }
            level = s.wifiLevel
        }
        findViewById<StatusIcon>(R.id.vpnIcon).level = if (s.vpn) 1 else 0
        findViewById<View>(R.id.offlineBanner).visibility = if (s.online) View.GONE else View.VISIBLE
        if (!s.online) {
            wasOffline = true
        } else if (wasOffline) {
            // connection is back: reload whatever failed while offline
            wasOffline = false
            states.clear()
            show(current)
        }
    }

    // ---------------------------------------------------------------- top-right pop-ups

    private fun popup() = GlassPopup(this)

    private fun showNetworkPopup() {
        val st = network.state
        val details = network.details().toMap()
        val p = popup().title(
            when {
                !st.online -> "Offline"
                st.ethernet -> "Ethernet"
                else -> details["Rete"] ?: "Wi-Fi"
            },
            if (st.online) "Connesso a Internet" else "Nessuna connessione a Internet",
        )
        details["Segnale"]?.let { p.info("Segnale", it.substringBefore(" (")) }
        val ping = p.info("Ping", "…")
        p.info("VPN", if (st.vpn) "Attiva" else "Non attiva")
        // device status
        val (freeDisk, totalDisk) = DeviceStatus.storage()
        val (freeRam, totalRam) = DeviceStatus.memory(this)
        p.info("Memoria libera", "${DeviceStatus.gb(freeDisk)} di ${DeviceStatus.gb(totalDisk)}")
        p.info("RAM libera", "${DeviceStatus.gb(freeRam)} di ${DeviceStatus.gb(totalRam)}")
        p.gap()
        p.button("Dettagli e test velocità") { openConnectionDetails() }
        p.button("Impostazioni rete") { runCatching { startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) } }
        val job = lifecycleScope.launch { ping.text = network.ping()?.let { "$it ms" } ?: "—" }
        p.onDismiss = { job.cancel() }
        p.showUnder(findViewById(R.id.netButton))
    }

    private fun installedVpns() = LauncherPrefs.VPN_APPS.filter { (pkg, _) -> apps.get(listOf(pkg)).isNotEmpty() }

    private fun showVpnPopup() {
        val vpn = network.state.vpn
        val installed = installedVpns()
        val chosen = installed.firstOrNull { it.first == prefs.vpnPackage }
        val p = popup().title("VPN", if (vpn) "Attiva · il traffico passa dalla VPN" else "Non attiva")
        p.info("App", chosen?.second ?: "Da scegliere")
        val ip = p.info("IP pubblico", "…")
        p.gap()
        when {
            installed.isEmpty() -> p.text("Nessuna app VPN installata.")
            chosen != null -> {
                p.button(if (vpn) "Apri ${chosen.second} per disattivarla" else "Apri ${chosen.second} per attivarla") { apps.launch(chosen.first) }
                p.button("Cambia app VPN") { chooseVpn() }
            }
            else -> p.button("Scegli l'app VPN") { chooseVpn() }
        }
        p.button("Dettagli connessione") { openConnectionDetails() }
        val job = lifecycleScope.launch { ip.text = network.publicIp() ?: "—" }
        p.onDismiss = { job.cancel() }
        p.showUnder(findViewById(R.id.vpnButton))
    }

    private fun chooseVpn() {
        val p = popup().title("Quale VPN vuoi usare?")
        installedVpns().forEach { (pkg, label) ->
            p.button(if (pkg == prefs.vpnPackage) "✓  $label" else label) {
                prefs.vpnPackage = pkg
                apps.launch(pkg)
            }
        }
        p.showUnder(findViewById(R.id.vpnButton))
    }

    private fun showSettingsPopup() {
        val p = popup().title("Impostazioni")
        p.button("Sfondo: " + LauncherPrefs.WALLPAPERS.first { it.first == prefs.wallpaper }.second) { chooseWallpaper() }
        lateinit var nightButton: TextView
        nightButton = p.button("Modalità notte: " + if (prefs.night) "attiva" else "spenta", keepOpen = true) {
            prefs.night = !prefs.night
            nightButton.text = "Modalità notte: " + if (prefs.night) "attiva" else "spenta"
            applyPrefs()
        }
        p.button("Rete e Wi-Fi") { runCatching { startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) } }
        p.button("Telecomandi e Bluetooth") { runCatching { startActivity(Intent("android.settings.CONTROLLERS_SETTINGS")) } }
        p.gap()
        p.button("Tutte le impostazioni") { openSettings() }
        p.showUnder(findViewById(R.id.settingsButton))
    }

    /** One "Sfondo" menu: the built-in wallpapers, your last image, or pick a new one from the device. */
    private fun chooseWallpaper() {
        val hasCustom = java.io.File(filesDir, WallpaperPickerActivity.CUSTOM_FILE).exists()
        val p = popup().title("Sfondo")
        LauncherPrefs.WALLPAPERS.filter { it.first != "custom" }.forEach { (key, label) ->
            p.button(if (key == prefs.wallpaper) "✓  $label" else label) {
                prefs.wallpaper = key
                applyPrefs()
            }
        }
        if (hasCustom) p.button(if (prefs.wallpaper == "custom") "✓  La tua immagine" else "La tua immagine") {
            prefs.wallpaper = "custom"
            applyPrefs()
        }
        p.button("Scegli immagine dal dispositivo…") { startActivity(Intent(this, WallpaperPickerActivity::class.java)) }
        p.showUnder(findViewById(R.id.settingsButton))
    }

    /** Wallpaper from the settings screen. */
    private fun applyPrefs() {
        GlassView.night = prefs.night
        val key = prefs.wallpaper + (if (prefs.wallpaper == "custom") prefs.customVersion.toString() else "") + (if (prefs.night) ":night" else "")
        if (key == appliedWallpaper) return
        appliedWallpaper = key
        lifecycleScope.launch {
            val source: android.graphics.Bitmap? = when (prefs.wallpaper) {
                "black" -> null
                "daily" -> runCatching {
                    val url = Tmdb.list("/trending/all/day").firstOrNull()?.backdrop ?: error("none")
                    val result = coil.ImageLoader(this@MainActivity).execute(
                        coil.request.ImageRequest.Builder(this@MainActivity).data(url).allowHardware(false).build()
                    )
                    (result.drawable as android.graphics.drawable.BitmapDrawable).bitmap
                }.getOrElse { withContext(Dispatchers.IO) { BitmapFactory.decodeResource(resources, R.drawable.wallpaper) } }
                "custom" -> withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(java.io.File(filesDir, WallpaperPickerActivity.CUSTOM_FILE).path)
                        ?: BitmapFactory.decodeResource(resources, R.drawable.wallpaper)
                }
                else -> withContext(Dispatchers.IO) { BitmapFactory.decodeResource(resources, R.drawable.wallpaper) }
            }
            val composed = withContext(Dispatchers.Default) { composeBackground(source) }
            findViewById<ImageView>(R.id.wallpaper).setImageBitmap(composed)
            setGlass(source)
        }
    }

    /**
     * The whole background as one screen-sized bitmap: wallpaper + the dark gradients on the left
     * and at the bottom baked in. The GPU then paints the background once per frame instead of
     * four full-screen layers (that overdraw made scrolling the rows stutter).
     */
    private fun composeBackground(source: android.graphics.Bitmap?): android.graphics.Bitmap {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        c.drawColor(0xFF000000.toInt())
        if (source != null) {
            // centre-crop, like the ImageView did
            val scale = maxOf(w.toFloat() / source.width, h.toFloat() / source.height)
            val sw = source.width * scale
            val sh = source.height * scale
            val dst = android.graphics.RectF((w - sw) / 2, (h - sh) / 2, (w + sw) / 2, (h + sh) / 2)
            c.drawBitmap(source, null, dst, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }
        val paint = android.graphics.Paint()
        val left = 640 * dm.density
        paint.shader = android.graphics.LinearGradient(0f, 0f, left, 0f,
            intArrayOf(0xF2000000.toInt(), 0x99000000.toInt(), 0x00000000), floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, left, h.toFloat(), paint)
        val bottom = 300 * dm.density
        paint.shader = android.graphics.LinearGradient(0f, h.toFloat(), 0f, h - bottom,
            intArrayOf(0xE6000000.toInt(), 0x66000000, 0x00000000), floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, h - bottom, w.toFloat(), h.toFloat(), paint)
        // night mode: the whole background much darker
        if (prefs.night) c.drawColor(0xB3000000.toInt())
        return out
    }

    private fun setGlass(source: android.graphics.Bitmap?) {
        lifecycleScope.launch {
            GlassView.blurred = source?.let { withContext(Dispatchers.Default) { GlassView.makeBlur(it) } }
            listOf(R.id.rightBar, R.id.dock, R.id.searchGlass).forEach { findViewById<View>(it).invalidate() }
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) userMoved = true
        // up from the carousel stops on the label at the top right (⟳ Altri per te / catalog picker) before the tabs
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP &&
            isSingle(current) && carousel.hasFocus() && catalogLabel.isShown
        ) {
            catalogLabel.requestFocus()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** carousel card to reopen per tab (Torna dove eri) */
    private val pendingCard = HashMap<Section, Int>()
    private var inForeground = false

    override fun onPause() {
        super.onPause()
        inForeground = false
        getSharedPreferences("where", MODE_PRIVATE).edit()
            .putString("section", current.name)
            .putInt("card", carouselAdapter?.active ?: 0)
            .putLong("at", System.currentTimeMillis())
            .apply()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already home: back to the top of Esplora
        if (intent.action == ACTION_CHECK_UPDATE) {
            checkForUpdateNow()
            return
        }
        if (intent.action == ACTION_OPEN_SPORT) {
            show(Section.SPORT, focusContent = true)
            return
        }
        if (intent.action == ACTION_LINK_NUVIO) {
            if (current != Section.EXPLORE) show(Section.EXPLORE)
            linkNuvio()
            return
        }
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            // coming back from an app: stay where you were. Home pressed again here: top of Esplora
            if (!inForeground) return
            if (current != Section.EXPLORE) show(Section.EXPLORE, focusContent = true) else goTop()
        }
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
        ensureBackgroundService()
        applyPrefs()
        network.refresh()
        refreshExploreLiveRows()
    }

    // ---------------------------------------------------------------- sections

    private fun specs(section: Section): List<Spec> = when (section) {
        Section.SEARCH -> emptyList()
        Section.EXPLORE -> buildList {
            add(Spec("perte", "Per te", RowKind.MIXED, mixed = ::perTe))
            if (nuvio.isConfigured) add(Spec("continue", "Continua a guardare", RowKind.LANDSCAPE, media = ::continueWatching))
            add(Spec("allapps", "Tutte le app", RowKind.APPS, apps = { apps.grid() }))
        }
        Section.MOVIES -> mediaSpecs("movie", listOf(
            "popular" to ("Popolari ora" to "/movie/popular"),
            "cinema" to ("Al cinema" to "/movie/now_playing"),
            "upcoming" to ("In arrivo" to "/movie/upcoming"),
        ))
        Section.SERIES -> mediaSpecs("series", listOf(
            "trend" to ("Di tendenza" to "/trending/tv/week"),
            "popular" to ("Popolari ora" to "/tv/popular"),
            "onair" to ("Nuovi episodi questa settimana" to "/tv/on_the_air"),
        ))
        Section.SPORT -> sportSpecs()
    }

    private fun mediaSpecs(type: String, tmdb: List<Pair<String, Pair<String, String>>>): List<Spec> = buildList {
        // generic catalogs first, then the Top 10s
        for ((key, v) in tmdb) add(Spec("${type}_$key", v.first, RowKind.LANDSCAPE, media = { Tmdb.list(v.second, type) }))
        for ((id, name) in TopX.services) add(Spec("${type}_tx_$id", "Top 10 su $name", RowKind.LANDSCAPE, media = { TopX.catalog(type, id) }))
    }

    private fun sportSpecs(): List<Spec> {
        // one fetch each, shared by "Oggi" and "Risultati"
        val today: Deferred<List<Match>> = lifecycleScope.async(start = CoroutineStart.LAZY) {
            runCatching { Espn.matchesOn(0) }.getOrElse { android.util.Log.w("Espn", "today", it); emptyList() }
        }
        val f1Today: Deferred<List<RaceSession>> = lifecycleScope.async(start = CoroutineStart.LAZY) {
            runCatching { Espn.f1Sessions(0..0, practice = true) }.getOrElse { emptyList() }
        }
        return listOf(
            // live first, then today's kick-offs and F1 sessions, by time
            Spec("sport_today", "Oggi", RowKind.MIXED, mixed = { sportToday(today.await(), f1Today.await()) }),
            Spec("sport_next", "Prossimi eventi", RowKind.MIXED, mixed = {
                val f1 = lifecycleScope.async { runCatching { Espn.f1Sessions(1..7, practice = true) }.getOrElse { emptyList() } }
                sortByTime(upcomingMatches() + f1.await())
            }),
            Spec("sport_f1", "Formula 1", RowKind.MIXED, mixed = {
                runCatching { Espn.f1Sessions(0..14, practice = true) }.getOrElse { emptyList() }.filter { it.state != "post" }
            }),
            Spec("sport_done", "Risultati", RowKind.MIXED, mixed = {
                sortByTime(today.await().filter { it.state == "post" } + f1Today.await().filter { it.state == "post" })
            }),
            Spec("sport_apps", "App sport", RowKind.APPS, apps = {
                apps.get(listOf(
                    "com.dazn", PrimeVideo.PKG, "it.mediaset.infinitytv1",
                    // NeroZone, sport and IPTV apps
                    "iptv.apupl",
                    "com.sportzx.live", "com.vegetatv.app",
                    "ar.tvplayer.tv", "com.nst.iptvsmarterstvbox", "com.iptvstreamsolutions.iptvplus",
                    "com.myiptvonline.implayer", "studio.scillarium.ottnavigator",
                ))
            }),
        )
    }

    private fun startOf(x: Any) = when (x) {
        is Match -> x.startMillis
        is RaceSession -> x.startMillis
        else -> 0L
    }

    private fun stateOf(x: Any) = when (x) {
        is Match -> x.state
        is RaceSession -> x.state
        else -> "pre"
    }

    private fun sortByTime(items: List<Any>): List<Any> = items.sortedBy { startOf(it) }

    /** Today, not finished: live first, then by start time. */
    private fun sportToday(matches: List<Match>, f1: List<RaceSession>): List<Any> =
        (matches + f1).filter { stateOf(it) != "post" }.sortedWith(compareBy({ if (stateOf(it) == "in") 0 else 1 }, { startOf(it) }))

    private suspend fun upcomingMatches(): List<Match> {
        val days = (1..7).map { d -> lifecycleScope.async { runCatching { Espn.matchesOn(d) }.getOrElse { emptyList() } } }
        return days.awaitAll().flatten().take(12)
    }

    private fun maxAge(section: Section) = when (section) {
        Section.SPORT -> 2 * 60_000L
        else -> 30 * 60_000L
    }

    private fun show(section: Section, focusContent: Boolean = false) {
        val changed = current != section || rowsView.tag == null
        current = section
        tabs.forEachIndexed { i, t ->
            t.isSelected = i == section.ordinal
            t.typeface = android.graphics.Typeface.create("sans-serif-medium", if (t.isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        findViewById<FocusGroup>(R.id.tabGroup).refresh()
        updateSearchTab()
        refreshDock()
        val singleMode = isSingle(section)
        val infoUnder = if (section == Section.EXPLORE || section == Section.SPORT) View.VISIBLE else View.GONE
        carouselTitle.visibility = infoUnder
        carouselMeta.visibility = infoUnder
        below.visibility = if (hasBelow(section)) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.bentoTitle).text = if (section == Section.SPORT) "App sport" else "Tutte le app"
        if (changed) { shownContinue = null; shownBento = null }
        if (changed) page.scrollTo(0, 0)
        single.visibility = if (singleMode) View.VISIBLE else View.GONE
        hero.visibility = if (singleMode) View.GONE else View.VISIBLE
        rowsView.visibility = if (singleMode) View.GONE else View.VISIBLE
        // Sport keeps its big clock; elsewhere the clock sits in the header
        // soft crossfade of the content when the tab changes
        if (changed) {
            rowsView.tag = section
            val content = if (singleMode) single else rowsView
            content.alpha = 0f
            content.animate().alpha(1f).setDuration(520).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
        searchBox.visibility = if (section == Section.SEARCH) View.VISIBLE else View.GONE
        if (singleMode) {
            val stale = states[section]?.let { System.currentTimeMillis() - it.loadedAt > maxAge(section) } ?: true
            val state = if (stale) SectionState(section, specs(section)).also { states[section] = it; load(it) } else states[section]!!
            shownCatalog = null
            bindSingle(state)
            if (focusContent) {
                userMoved = false
                focusFirstCard()
            }
            return
        }
        if (section == Section.SEARCH) {
            adapter.submit(searchRows)
            hideHero()
        } else {
            val stale = states[section]?.let { System.currentTimeMillis() - it.loadedAt > maxAge(section) } ?: true
            val state = if (stale) SectionState(section, specs(section)).also { states[section] = it; load(it) } else states[section]!!
            adapter.submit(state.visible())
            showGreeting()
        }
        rowsView.scrollToPosition(0)
        if (focusContent) {
            userMoved = false
            focusFirstCard()
        }
    }

    private fun load(state: SectionState) {
        state.loadedAt = System.currentTimeMillis()
        state.specs.forEachIndexed { i, spec ->
            spec.apps?.let {
                state.rows[i].apps = it()
                state.ready[i] = true
            }
            val media = spec.media
            val matches = spec.matches
            val mixed = spec.mixed
            if (media != null || matches != null || mixed != null) lifecycleScope.launch {
                if (media != null) state.rows[i].media = runCatching { media() }.getOrElse { emptyList() }
                if (matches != null) state.rows[i].matches = runCatching { matches() }.getOrElse { emptyList() }
                if (mixed != null) state.rows[i].items = runCatching { mixed() }.getOrElse { emptyList() }
                state.ready[i] = true
                if (states[state.section] === state && current == state.section) publish(state)
            }
        }
    }

    /** Insert newly loaded rows without rebuilding the list, so focus stays where it is. */
    private fun publish(state: SectionState) {
        if (isSingle(state.section)) {
            if (current == state.section) bindSingle(state)
            return
        }
        val visible = state.visible()
        val shown = adapter.rows.toMutableList()
        var insertedAtTop = false
        visible.forEachIndexed { index, row ->
            if (row !in shown) {
                shown.add(index, row)
                adapter.insert(index, row)
                if (index == 0) insertedAtTop = true
            }
        }
        // RecyclerView keeps its anchor, so a row inserted above would stay hidden off-screen.
        // Bring it into view unless the user is already browsing further down.
        if (insertedAtTop && !userMoved) {
            rowsView.scrollToPosition(0)
            focusFirstCard()
        } else if (insertedAtTop && !rowsView.hasFocus()) rowsView.scrollToPosition(0)
        else if (insertedAtTop && rowsView.focusedChild?.let { rowsView.getChildAdapterPosition(it) } == 1) rowsView.scrollToPosition(0)
    }

    private fun focusFirstCard() {
        if (isSingle(current)) {
            carousel.post {
                val target = carouselAdapter?.let { carousel.layoutManager?.findViewByPosition(it.active) } ?: carousel.getChildAt(0)
                target?.requestFocus() ?: tabs[current.ordinal].requestFocus()
            }
            return
        }
        rowsView.post {
            val vh = rowsView.findViewHolderForAdapterPosition(0) as? RowsAdapter.VH
            vh?.list?.getChildAt(0)?.requestFocus() ?: tabs[current.ordinal].requestFocus()
        }
    }

    private fun goTop() {
        page.glideTo(0)
        rowsView.smoothScrollToPosition(0)
        tabs[current.ordinal].requestFocus()
    }

    private var searchAnim: android.animation.ValueAnimator? = null
    private var searchTarget = -1

    /**
     * Cerca: just "⌕" when not in use; widens to "⌕  Cerca" when focused or open.
     * The label never changes: the tab's width slides open/closed and clips it, like a curtain,
     * and the white pill follows the edges (FocusGroup).
     */
    private fun updateSearchTab(animate: Boolean = true) {
        val t = tabs[0]
        // open while selected; on the Cerca page also while you're typing/browsing results,
        // but it closes as soon as the highlight moves to another tab
        val tabsFocused = findViewById<View>(R.id.tabGroup).hasFocus()
        val wide = t.isFocused || (current == Section.SEARCH && !tabsFocused)
        val collapsed = t.paddingLeft + t.paint.measureText("⌕").toInt() + t.paddingRight
        val full = t.paddingLeft + t.paint.measureText(t.text.toString()).toInt() + t.paddingRight + 2
        val target = if (wide) full else collapsed
        if (target == searchTarget) return
        searchTarget = target
        searchAnim?.cancel()
        val lp = t.layoutParams
        val from = if (lp.width > 0) lp.width else t.width.takeIf { it > 0 } ?: target
        if (!animate || from == target) {
            lp.width = target
            t.layoutParams = lp
            return
        }
        searchAnim = android.animation.ValueAnimator.ofInt(from, target).apply {
            duration = 560 // ≈280 ms at the 0.5x animation scale
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener {
                lp.width = it.animatedValue as Int
                t.layoutParams = lp
            }
            start()
        }
    }

    private fun setupTabs() {
        // entering the tab bar from below or from the right lands directly on the active tab
        findViewById<FocusGroup>(R.id.tabGroup).apply {
            entry = { tabs[current.ordinal] }
            showSelected = false
        }
        // Cerca: just the icon, expands to "⌕  Cerca" when selected or in use
        tabs[0].apply {
            setHorizontallyScrolling(true)
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            setOnFocusChangeListener { _, _ -> }
            post { updateSearchTab(animate = false) }
        }
        tabs.forEach { tab ->
            tab.setOnClickListener { if (current == Section.SEARCH) searchInput.requestFocus() else focusFirstCard() }
        }
        // Entering the tab bar from the rows lands on the active tab, never on a neighbour;
        // moving between tabs switches section.
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            updateSearchTab()
            val newTab = tabs.indexOf(new)
            if (newTab < 0) {
                // passed through the tabs on the way to the top-right buttons: don't switch
                tabJob?.cancel()
                return@addOnGlobalFocusChangeListener
            }
            tabJob?.cancel()
            tabJob = lifecycleScope.launch {
                delay(220)
                if (Section.entries[newTab] != current) show(Section.entries[newTab])
            }
        }
    }

    // ---------------------------------------------------------------- search

    private fun setupSearch() {
        findViewById<View>(R.id.voiceButton).setOnClickListener { startVoiceSearch() }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(450)
                    runSearch(s?.toString().orEmpty().trim())
                }
            }
        })
        searchInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchInput.windowToken, 0)
                searchJob?.cancel()
                lifecycleScope.launch {
                    runSearch(searchInput.text.toString().trim())
                    focusFirstCard()
                }
                true
            } else false
        }
    }

    /**
     * 🎤 Voce: Fire OS gives apps no speech engine, but its keyboard does dictation:
     * open the keyboard on the search field and hold the remote's Alexa button to speak.
     */
    private fun startVoiceSearch() {
        searchInput.requestFocus()
        searchInput.setText("")
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(searchInput, InputMethodManager.SHOW_FORCED)
        Toast.makeText(this, "Tieni premuto il tasto Alexa (🎤) sul telecomando e di' cosa cerchi", Toast.LENGTH_LONG).show()
    }

    private suspend fun runSearch(query: String) {
        if (query.length < 2) {
            searchRows = emptyList()
        } else {
            val appMatches = apps.all().filter { it.label.contains(query, ignoreCase = true) }
            val movies = lifecycleScope.async { runCatching { Tmdb.search("movie", query) }.getOrElse { emptyList() } }
            val series = lifecycleScope.async { runCatching { Tmdb.search("series", query) }.getOrElse { emptyList() } }
            searchRows = listOfNotNull(
                Row("s_apps", "App", RowKind.APPS, apps = appMatches).takeIf { appMatches.isNotEmpty() },
                Row("s_movie", "Film", RowKind.LANDSCAPE, media = movies.await()).takeIf { it.size > 0 },
                Row("s_series", "Serie TV", RowKind.LANDSCAPE, media = series.await()).takeIf { it.size > 0 },
            )
        }
        if (current == Section.SEARCH) adapter.submit(searchRows)
    }

    // ---------------------------------------------------------------- single screen

    /** "Per te": today's Napoli matches and Ferrari's F1 sessions, then films and series alternated. */
    private suspend fun perTe(): List<Any> = kotlinx.coroutines.coroutineScope {
        val napoli = async { runCatching { Espn.napoliToday() }.getOrElse { emptyList() }.filter { it.state != "post" } }
        val f1 = async { runCatching { Espn.f1Today() }.getOrElse { emptyList() }.filter { it.state != "post" } }
        val picks = async { runCatching { personalPicks() }.getOrElse { emptyList() } }
        val movies = async {
            picks.await().filter { it.type == "movie" }.ifEmpty { runCatching { Tmdb.list("/trending/movie/week", "movie", page = perTePage + 1) }.getOrElse { emptyList() } }
        }
        val series = async {
            picks.await().filter { it.type == "series" }.ifEmpty { runCatching { Tmdb.list("/trending/tv/week", "series", page = perTePage + 1) }.getOrElse { emptyList() } }
        }
        val sport: List<Any> = (napoli.await() + f1.await()).sortedBy {
            when (it) {
                is Match -> it.startMillis
                is RaceSession -> it.startMillis
                else -> 0L
            }
        }
        val m = movies.await()
        val t = series.await()
        val all = (0 until maxOf(m.size, t.size)).flatMap { listOfNotNull(m.getOrNull(it), t.getOrNull(it)) }
        // each refresh shows the next 16 (wrapping round), so "Altri per te" brings new titles
        val start = if (all.isEmpty()) 0 else (perTePage * 16) % all.size
        val mixed = (all.drop(start) + all.take(start)).take(16)
        sport + mixed
    }

    /**
     * Suggestions from your Nuvio history: TMDB recommendations for what you're watching and what's in
     * your library, most-recommended first, without what you already have.
     */
    /** which batch of "Per te" is shown; bumped by the refresh button */
    private var perTePage = 0
    private var refreshing = false

    /** "⟳ Altri per te": next batch of suggestions, back on the first card. */
    private fun refreshPerTe() {
        if (refreshing) return
        val state = states[Section.EXPLORE] ?: return
        val i = state.specs.indexOfFirst { it.key == "perte" }
        if (i < 0) return
        refreshing = true
        perTePage++
        catalogLabel.text = "Aggiorno…"
        lifecycleScope.launch {
            val items = runCatching { perTe() }.getOrNull()
            refreshing = false
            if (items != null) state.rows[i].items = items
            shownCatalog = null
            if (current == Section.EXPLORE) {
                userMoved = false
                bindSingle(state)
                focusFirstCard()
            }
        }
    }

    private suspend fun personalPicks(): List<MediaItem> = kotlinx.coroutines.coroutineScope {
        if (!nuvio.isLinked) return@coroutineScope emptyList()
        val watching = runCatching { nuvio.continueWatching() }.getOrElse { emptyList() }
        val library = runCatching { nuvio.library() }.getOrElse { emptyList() }
        val seeds = (watching + library).filter { it.tmdbId != null }.distinctBy { it.type to it.tmdbId }.take(10)
        val owned = (watching + library).mapNotNull { it.tmdbId }.toSet()
        val lists = seeds.map { s -> async { runCatching { Tmdb.recommendations(s.type, s.tmdbId!!) }.getOrElse { emptyList() }.take(20) } }.awaitAll()
        // why: the title of yours that recommends it most strongly
        val because = HashMap<Pair<String, Int>, Pair<String, Double>>()
        val inProgress = watching.mapNotNull { it.tmdbId }.toSet()
        // score: how many of your titles recommend it, then how high
        val score = HashMap<Pair<String, Int>, Double>()
        val byKey = HashMap<Pair<String, Int>, MediaItem>()
        lists.forEach { l ->
            l.forEachIndexed { rank, m ->
                val id = m.tmdbId ?: return@forEachIndexed
                if (id in owned) return@forEachIndexed
                val k = m.type to id
                val w = 1.0 + (10 - rank) / 20.0
                score[k] = (score[k] ?: 0.0) + w
                byKey[k] = m
                val seed = seeds[lists.indexOf(l)]
                if (w > (because[k]?.second ?: -1.0)) {
                    val verb = if (seed.tmdbId in inProgress) "stai guardando" else "hai in libreria"
                    because[k] = "Perché $verb ${seed.title}" to w
                }
            }
        }
        score.entries.sortedByDescending { it.value }.mapNotNull { e -> byKey[e.key]?.copy(reason = because[e.key]?.first) }.take(80)
    }

    // ---------------------------------------------------------------- Esplora: below the dock

    /** Continua a guardare and the bento grid, rebuilt only when their content changes. */
    private fun bindBelow(state: SectionState) {
        val cw = if (state.section == Section.EXPLORE) state.rows.firstOrNull { it.key == "continue" }?.media.orEmpty() else emptyList()
        if (cw != shownContinue) {
            shownContinue = cw
            continueList.adapter = CardsAdapter(Row("continue", "", RowKind.LANDSCAPE, media = cw), this)
            findViewById<View>(R.id.continueTitle).visibility = if (cw.isEmpty()) View.GONE else View.VISIBLE
            continueList.visibility = if (cw.isEmpty()) View.GONE else View.VISIBLE
        }
        val inDock = apps.dock(sportDock).toSet()
        val all = state.rows.firstOrNull { it.key == "allapps" || it.key == "sport_apps" }?.apps.orEmpty().filter { it.pkg !in inDock }
        if (all.map { it.pkg } != shownBento) {
            shownBento = all.map { it.pkg }
            buildBento(all)
        }
    }

    /** All apps: rows of 7 small tiles alternating with rows of 3 large ones. */
    private fun buildBento(all: List<AppInfo>) {
        bento.removeAllViews()
        val dp = resources.displayMetrics.density
        val gap = (14 * dp).toInt()
        val width = resources.displayMetrics.widthPixels - bento.paddingLeft - bento.paddingRight
        var i = 0
        var small = true
        while (i < all.size) {
            val n = if (small) 7 else 3
            val w = (width - gap * (n - 1)) / n
            val h = w * 9 / 16
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                clipChildren = false
            }
            val chunk = all.subList(i, minOf(i + n, all.size))
            chunk.forEachIndexed { k, app ->
                val tile = appTile(app, small).apply { id = View.generateViewId() }
                // left/right stop at the ends of the row instead of jumping to another row
                if (k == 0) tile.nextFocusLeftId = tile.id
                if (k == chunk.size - 1) tile.nextFocusRightId = tile.id
                row.addView(tile, android.widget.LinearLayout.LayoutParams(w, h).apply {
                    if (k > 0) marginStart = gap
                })
            }
            bento.addView(row, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (i == 0) (10 * dp).toInt() else gap })
            i += n
            small = !small
        }
    }

    private fun appTile(app: AppInfo, small: Boolean): View {
        val dp = resources.displayMetrics.density
        return android.widget.FrameLayout(this).apply {
            isFocusable = true
            isClickable = true
            contentDescription = app.label
            setBackgroundResource(R.drawable.card_bg)
            foreground = getDrawable(R.drawable.card_focus)
            clipToOutline = true
            if (app.banner != null) {
                addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageDrawable(app.banner)
                }, android.widget.FrameLayout.LayoutParams(-1, -1))
            } else {
                val pad = ((if (small) 14 else 30) * dp).toInt()
                addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageDrawable(app.icon)
                    setPadding(pad, pad / 2, pad, pad + (if (small) 6 * dp else 14 * dp).toInt())
                }, android.widget.FrameLayout.LayoutParams(-1, -1))
                addView(TextView(context).apply {
                    text = app.label
                    textSize = if (small) 9f else 13f
                    maxLines = 1
                    gravity = android.view.Gravity.CENTER
                    setTextColor(0xFFFFFFFF.toInt())
                }, android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM).apply {
                    bottomMargin = ((if (small) 4 else 10) * dp).toInt()
                })
            }
            setOnClickListener { onAppClicked(app) }
            setOnLongClickListener { onAppMenu(app); true }
            setOnKeyListener { _, code, ev ->
                if (code != android.view.KeyEvent.KEYCODE_MENU) return@setOnKeyListener false
                if (ev.action == android.view.KeyEvent.ACTION_UP) onAppMenu(app)
                true
            }
            setOnFocusChangeListener { v, has ->
                val sc = if (has) 1.06f else 1f
                v.animate().scaleX(sc).scaleY(sc).setDuration(440).setInterpolator(android.view.animation.DecelerateInterpolator(1.4f)).start()
            }
        }
    }

    /** Esplora's page: top while on the first screen, otherwise keep the focused row near the top. */
    private fun followFocus(v: View?) {
        if (v == null || !hasBelow(current)) return
        if (!isInside(v, below)) {
            if (page.scrollY != 0) page.glideTo(0)
            return
        }
        val dp = resources.displayMetrics.density
        // the row the focus is in, relative to the page content
        var row: View = v
        while (row.parent !== below && row.parent !== bento && row.parent is View) row = row.parent as View
        var y = row.top
        var p = row.parent as? View
        while (p != null && p.id != R.id.pageContent) { y += p.top; p = p.parent as? View }
        val target = if (isInside(v, continueList)) y - (60 * dp).toInt() else y - (140 * dp).toInt()
        val max = (page.getChildAt(0).height - page.height).coerceAtLeast(0)
        page.glideTo(target.coerceIn(0, max))
    }

    private fun isInside(v: View, parent: View): Boolean {
        var x: View? = v
        while (x != null) {
            if (x === parent) return true
            x = x.parent as? View
        }
        return false
    }

    private fun itemsOf(row: Row): List<Any> = when (row.kind) {
        RowKind.MIXED -> row.items
        RowKind.MATCH -> row.matches
        RowKind.APPS, RowKind.DOCK -> row.apps
        else -> row.media
    }

    /** Show the chosen catalog of a single-screen tab in the carousel (only rebinds when its content changed). */
    private fun bindSingle(state: SectionState) {
        val explore = state.section == Section.EXPLORE
        if (hasBelow(state.section)) bindBelow(state)
        // Esplora has no picker: the carousel is always "Per te"

        var index = if (explore) 0 else (catalogIndex[state.section] ?: 0).coerceIn(0, (state.rows.size - 1).coerceAtLeast(0))
        // the chosen catalog came back empty: fall back to the first one with something in it
        if (!explore && state.ready.getOrElse(index) { false } && state.rows.getOrNull(index)?.size == 0) {
            state.rows.indices.firstOrNull { state.ready[it] && state.rows[it].size > 0 }?.let { index = it }
        }
        val row = state.rows.getOrNull(index) ?: return
        catalogLabel.text = if (explore) (if (refreshing) "Aggiorno…" else "⟳  Altri per te") else "${row.title}  ▾"
        val items = itemsOf(row)
        if (row === shownCatalog && items == shownItems) {
            showActiveInfo()
            return
        }
        val first = shownItems.isEmpty() || row !== shownCatalog
        shownCatalog = row
        shownItems = items
        carouselAdapter = CarouselAdapter(items, this, lifecycleScope)
        val restore = pendingCard.remove(state.section)?.takeIf { it in items.indices }
        if (restore != null) carouselAdapter!!.startAt(restore)
        carousel.adapter = carouselAdapter
        carousel.scrollToPosition(restore ?: 0)
        when (val head = items.getOrNull(carouselAdapter?.active ?: 0)) {
            is MediaItem -> onMediaFocusedInfo(head)
            is Match -> onMatchFocused(head)
            is AppInfo -> setCarouselInfo(head.label, "App")
            is RaceSession -> onRaceFocused(head)
            else -> setCarouselInfo(if (state.ready[index]) "" else "Caricamento…", "")
        }
        if (first && items.isNotEmpty() && !userMoved) focusFirstCard()
    }

    /** Title/details under the carousel for its wide card (e.g. after coming back to the tab). */
    private fun showActiveInfo() {
        val i = carouselAdapter?.active ?: 0
        when (val item = shownItems.getOrNull(i)) {
            is MediaItem -> onMediaFocusedInfo(item)
            is Match -> onMatchFocused(item)
            is RaceSession -> onRaceFocused(item)
            is AppInfo -> setCarouselInfo(item.label, "App")
            else -> setCarouselInfo("", "")
        }
    }

    private fun setCarouselInfo(title: String, meta: String) {
        carouselTitle.text = title
        carouselMeta.text = meta
    }

    /** "Popolari ora ▾": switch the carousel to another catalog of this tab. */
    private fun showCatalogPicker() {
        val state = states[current] ?: return
        if (!isSingle(current) || current == Section.EXPLORE) return
        val p = popup().title("Cosa vuoi vedere?")
        val selected = catalogIndex[current] ?: 0
        state.rows.forEachIndexed { i, row ->
            if (row.kind == RowKind.APPS || (state.ready[i] && row.size == 0)) return@forEachIndexed
            val label = if (state.ready[i]) row.title else "${row.title} (caricamento…)"
            p.button(if (i == selected) "✓  $label" else label) {
                catalogIndex[current] = i
                shownCatalog = null
                bindSingle(state)
                focusFirstCard()
            }
        }
        if (current == Section.SPORT) {
            p.gap()
            p.button("Classifica Serie A") { openSportPage(SportPagesActivity.SERIE_A) }
            p.button("Classifica F1 · Piloti") { openSportPage(SportPagesActivity.F1_DRIVERS) }
            p.button("Classifica F1 · Costruttori") { openSportPage(SportPagesActivity.F1_TEAMS) }
            p.button("Calendario Napoli") { openSportPage(SportPagesActivity.NAPOLI) }
        }
        p.showUnder(catalogLabel)
    }

    /** Favourite apps in the dock at the bottom of the single-screen tabs. */
    private fun refreshDock(keepFocusOn: String? = null) {
        val favs = apps.get(apps.dock(sportDock))
        if (favs.map { it.pkg } == dockApps) return
        dockApps = favs.map { it.pkg }
        // rebuilding the dock drops the focus: keep it on the same app (or same slot) so it doesn't
        // fall onto the first tab and switch section
        val focusedPkg = keepFocusOn ?: if (dockList.hasFocus()) dockList.focusedChild?.contentDescription?.toString() else null
        val focusedPos = dockList.focusedChild?.let { dockList.getChildAdapterPosition(it) } ?: 0
        if (focusedPkg != null) dockList.requestFocus()
        dockList.adapter = CardsAdapter(Row("dock", "", RowKind.DOCK, apps = favs), this)
        if (focusedPkg != null) {
            val target = favs.indexOfFirst { it.label == focusedPkg }.takeIf { it >= 0 } ?: focusedPos.coerceIn(0, (favs.size - 1).coerceAtLeast(0))
            // after the pop-up has handed focus back to the window
            dockList.postDelayed({ dockList.layoutManager?.findViewByPosition(target)?.requestFocus() }, 120)
        }
        // apps in the dock don't repeat in the grid below
        states[current]?.let { if (hasBelow(current)) bindBelow(it) }
    }

    // ---------------------------------------------------------------- live rows

    private suspend fun continueWatching(): List<MediaItem> =
        if (!nuvio.isLinked) listOf(MediaItem(title = "Collega Nuvio per vedere i tuoi progressi", type = "movie", action = MediaItem.Action.LINK_NUVIO))
        else nuvio.continueWatching()

    /** Recents, favourites and Nuvio progress/library change while the user is in other apps. */
    private fun refreshExploreLiveRows() {
        val state = states[Section.EXPLORE] ?: return
        apps.invalidate()
        refreshDock()
        updateAppsRow(state, "allapps") { apps.grid() }
        lifecycleScope.launch {
            val cw = runCatching { continueWatching() }.getOrNull() ?: return@launch
            val ci = state.specs.indexOfFirst { it.key == "continue" }
            if (ci >= 0) { state.rows[ci].media = cw; state.ready[ci] = true }
            if (current == Section.EXPLORE) bindBelow(state)
        }
        val i = state.specs.indexOfFirst { it.key == "perte" }
        if (i >= 0) lifecycleScope.launch {
            val items = runCatching { perTe() }.getOrNull() ?: return@launch
            if (items == state.rows[i].items) return@launch
            state.rows[i].items = items
            state.ready[i] = true
            publish(state)
        }
    }

    private fun refreshMediaRow(state: SectionState, key: String, load: suspend () -> List<MediaItem>) {
        val i = state.specs.indexOfFirst { it.key == key }
        if (i < 0) return
        lifecycleScope.launch {
            val items = runCatching { load() }.getOrNull() ?: return@launch
            val row = state.rows[i]
            if (items == row.media) return@launch
            row.media = items
            state.ready[i] = true
            if (current != Section.EXPLORE) return@launch
            when {
                row in adapter.rows && items.isEmpty() -> adapter.remove(row)
                row in adapter.rows -> adapter.rowChanged(row)
                else -> publish(state)
            }
        }
    }

    private fun rebuildExplore() {
        states.remove(Section.EXPLORE)
        if (current == Section.EXPLORE) show(Section.EXPLORE, focusContent = true)
    }

    private fun updateAppsRow(state: SectionState, key: String, load: () -> List<AppInfo>) {
        val i = state.specs.indexOfFirst { it.key == key }
        if (i < 0) return
        val row = state.rows[i]
        val fresh = load()
        if (fresh.map { it.pkg } == row.apps.map { it.pkg }) return
        row.apps = fresh
        if (current != state.section) return
        when {
            row in adapter.rows && fresh.isEmpty() -> adapter.remove(row)
            row in adapter.rows -> adapter.rowChanged(row)
            else -> publish(state)
        }
    }

    /** While the Sport tab is open, refresh "Oggi"/"Risultati" every minute without moving the focus. */
    private fun tickLiveScores() {
        lifecycleScope.launch {
            while (isActive) {
                delay(60_000)
                val state = states[Section.SPORT] ?: continue
                if (current != Section.SPORT) continue
                val matches = runCatching { Espn.matchesOn(0) }.getOrNull() ?: continue
                val f1 = runCatching { Espn.f1Sessions(0..0, practice = true) }.getOrElse { emptyList() }
                val todayRow = state.rows.first { it.key == "sport_today" }
                val doneRow = state.rows.first { it.key == "sport_done" }
                val newToday = sportToday(matches, f1)
                val newDone = sortByTime(matches.filter { it.state == "post" } + f1.filter { it.state == "post" })
                fun ids(l: List<Any>) = l.map { if (it is Match) it.id else (it as RaceSession).let { r -> r.grandPrix + r.session } }
                val sameShape = ids(todayRow.items) == ids(newToday) && ids(doneRow.items) == ids(newDone)
                todayRow.items = newToday
                doneRow.items = newDone
                if (!sameShape) {
                    // something started or ended: rebuild the carousel
                    shownCatalog = null
                    bindSingle(state)
                    continue
                }
                // only scores/minutes changed: update the cards in place, focus stays put
                val shown = shownCatalog ?: continue
                val items = itemsOf(shown)
                if (items != shownItems) {
                    shownItems = items
                    carouselAdapter?.update(items)
                }
            }
        }
    }

    // ---------------------------------------------------------------- hero

    override fun onMediaFocused(item: MediaItem) {
        if (isSingle(current) && !carousel.hasFocus()) return
        if (isSingle(current)) {
            onMediaFocusedInfo(item)
            return
        }
        heroMediaFocused(item)
    }

    private fun onMediaFocusedInfo(item: MediaItem) {
        if (item.action == MediaItem.Action.LINK_NUVIO) setCarouselInfo("Collega Nuvio", "Premi OK per vedere qui i tuoi progressi")
        else setCarouselInfo(item.title, listOfNotNull(
            if (item.type == "movie") "Film" else "Serie TV",
            item.year,
            item.rating?.let { "★ $it" },
            item.badge,
            item.progress?.let { "${(it * 100).toInt()}% visto" },
            item.reason,
        ).joinToString(" · "))
    }

    /** Old-style hero at the top (Cerca). */
    private fun heroMediaFocused(item: MediaItem) {
        heroJob?.cancel()
        heroJob = lifecycleScope.launch {
            delay(160)
            if (current == Section.SEARCH) return@launch
            if (item.action == MediaItem.Action.LINK_NUVIO) {
                showInfo("Nuvio Sync", "CONTINUA A GUARDARE", "Collega il tuo account Nuvio: qui compariranno i film e gli episodi che stai guardando e la tua libreria.", null)
                return@launch
            }
            val meta = listOfNotNull(
                if (item.type == "movie") "FILM" else "SERIE TV",
                item.year,
                item.rating?.let { "★ $it" },
                item.badge,
                item.progress?.let { "${(it * 100).toInt()}% visto" },
            ).joinToString("  ·  ")
            showInfo(item.title, meta, item.overview, item.logo)
        }
    }

    override fun onAppFocused(app: AppInfo) {
        if (isSingle(current)) {
            // apps in the carousel ("Tutte le app") describe themselves; the dock leaves the info alone
            if (carousel.hasFocus()) setCarouselInfo(app.label, "App")
            return
        }
        heroJob?.cancel()
        heroJob = lifecycleScope.launch {
            delay(120)
            if (current != Section.SEARCH) showGreeting()
        }
    }

    override fun onMatchFocused(match: Match) {
        if (isSingle(current)) {
            val whereText = when {
                PrimeVideo.shows(match) -> "Prime Video"
                match.state == "post" -> null
                else -> "Nuvio"
            }
            val whenText = when (match.state) {
                "in" -> "In diretta · ${match.detail}"
                "post" -> "Finale ${match.homeScore ?: 0}-${match.awayScore ?: 0}"
                else -> {
                    val isToday = Espn.dayLabel(match) == SimpleDateFormat("EEE d MMM", Locale.ITALY).format(Date())
                    (if (isToday) "Oggi" else Espn.dayLabel(match).replaceFirstChar { it.uppercase() }) + " ${Espn.kickoff(match)}"
                }
            }
            setCarouselInfo("${match.home} – ${match.away}", listOfNotNull(whenText, whereText, match.league.label).joinToString(" · "))
            return
        }
        heroJob?.cancel()
        heroJob = lifecycleScope.launch {
            delay(120)
            val score = "${match.homeScore ?: "0"} - ${match.awayScore ?: "0"}"
            val whenText = when (match.state) {
                "in" -> "IN DIRETTA  ·  ${match.detail}  ·  $score"
                "post" -> "FINALE  ·  $score"
                else -> {
                    val isToday = Espn.dayLabel(match) == SimpleDateFormat("EEE d MMM", Locale.ITALY).format(Date())
                    (if (isToday) "OGGI" else Espn.dayLabel(match).uppercase()) + " ORE ${Espn.kickoff(match)}"
                }
            }
            val action = when {
                PrimeVideo.shows(match) -> "In esclusiva su Prime Video · premi OK per aprirlo"
                match.state == "in" -> "Premi OK per aprirla in Nuvio"
                match.state == "pre" -> "Disponibile in Nuvio dal calcio d'inizio"
                else -> "Partita terminata"
            }
            val details = listOfNotNull(match.venue, action).joinToString("\n")
            showInfo("${match.home} – ${match.away}", "${match.league.label.uppercase()}  ·  $whenText", details, null)
        }
    }

    private fun showInfo(title: String, meta: String, overview: String?, logo: String?) {
        infoMeta.text = meta
        infoOverview.text = overview.orEmpty()
        infoTitle.text = title
        if (logo != null) {
            infoTitle.visibility = View.INVISIBLE
            infoLogo.load(logo) {
                crossfade(true)
                listener(onError = { _, _ -> infoTitle.visibility = View.VISIBLE })
            }
        } else {
            infoLogo.dispose()
            infoLogo.setImageDrawable(null)
            infoTitle.visibility = View.VISIBLE
        }
        greeting.animate().alpha(0f).setDuration(150).start()
        info.animate().alpha(1f).setDuration(200).start()
    }

    private fun showGreeting() {
        heroJob?.cancel()
        info.animate().alpha(0f).setDuration(150).start()
        greeting.animate().alpha(1f).setDuration(200).start()
    }

    private fun hideHero() {
        heroJob?.cancel()
        info.animate().alpha(0f).setDuration(100).start()
        greeting.animate().alpha(0f).setDuration(100).start()
    }

    private fun tickClock() {
        lifecycleScope.launch {
            while (isActive) {
                val now = Date()
                val date = SimpleDateFormat("EEEE d MMMM", Locale.ITALY).format(now)
                dateView.text = date.replaceFirstChar { it.uppercase() }
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                helloView.text = when (hour) {
                    in 5..12 -> "Buongiorno, Nello"
                    in 13..17 -> "Buon pomeriggio, Nello"
                    else -> "Buonasera, Nello"
                }
                delay(30_000)
            }
        }
    }

    // ---------------------------------------------------------------- actions

    override fun onMediaClicked(item: MediaItem) {
        if (item.action == MediaItem.Action.LINK_NUVIO) {
            linkNuvio()
            return
        }
        if (item.imdbId != null && item.videoId != null) {
            if (!apps.resumeInNuvio(item, item.imdbId, item.videoId)) toast("Nuvio non è installato")
            return
        }
        if (item.imdbId == null && item.contentId != null) {
            if (!apps.openNuvioContent(item.contentId, item.type)) toast("Nuvio non è installato")
            return
        }
        lifecycleScope.launch {
            val imdb = item.imdbId ?: item.tmdbId?.let { runCatching { Tmdb.imdbId(item.type, it) }.getOrNull() }
            when {
                imdb == null -> toast("Titolo non trovato su IMDb")
                !apps.openInNuvio(item, imdb) -> toast("Nuvio non è installato")
            }
        }
    }

    override fun onAppClicked(app: AppInfo) {
        if (app.pkg == Apps.HIDDEN_TILE) {
            showHiddenApps()
            return
        }
        if (!apps.launch(app.pkg)) toast("Impossibile aprire ${app.label}")
    }

    override fun onMatchClicked(match: Match) {
        if (current == Section.EXPLORE) {
            // "Per te": always in Nuvio
            when (match.state) {
                "pre" -> toast("${match.home} – ${match.away} sarà in Nuvio dal calcio d'inizio (${Espn.kickoff(match)})")
                "post" -> toast("Partita terminata")
                else -> lifecycleScope.launch {
                    val id = runCatching { SportsAddon.findAnyMatch(match) }.getOrNull()
                    when {
                        id == null -> toast("Partita non ancora disponibile in Sports Streams, riprova tra poco")
                        !apps.openNuvioContent(id, "sport") -> toast("Nuvio non è installato")
                    }
                }
            }
            return
        }
        if (PrimeVideo.shows(match)) {
            if (!apps.launch(PrimeVideo.PKG)) toast("Prime Video non è installato")
            return
        }
        when (match.state) {
            "pre" -> toast("${match.home} – ${match.away} sarà in Nuvio dal calcio d'inizio (${Espn.kickoff(match)})")
            "post" -> toast("Partita terminata")
            else -> lifecycleScope.launch {
                val addonId = runCatching { SportsAddon.findId(match) }.getOrNull()
                when {
                    addonId == null -> toast("Partita non ancora disponibile in Sports Streams, riprova tra poco")
                    !apps.openNuvioContent(addonId, "sport") -> toast("Nuvio non è installato")
                }
            }
        }
    }

    override fun onMatchDetails(match: Match) {
        startActivity(Intent(this, SportPagesActivity::class.java)
            .putExtra(SportPagesActivity.EXTRA_PAGE, SportPagesActivity.MATCH)
            .putExtra(SportPagesActivity.EXTRA_EVENT, match.id)
            .putExtra(SportPagesActivity.EXTRA_LEAGUE, match.league.espn))
    }

    private fun openSportPage(page: String) =
        startActivity(Intent(this, SportPagesActivity::class.java).putExtra(SportPagesActivity.EXTRA_PAGE, page))

    override fun onRaceFocused(race: RaceSession) {
        val day = SimpleDateFormat("EEE d MMM", Locale.ITALY).apply { timeZone = java.util.TimeZone.getTimeZone("Europe/Rome") }
        val isToday = day.format(race.startMillis) == day.format(System.currentTimeMillis())
        val whenText = when (race.state) {
            "in" -> "In diretta"
            "post" -> "Conclusa"
            else -> (if (isToday) "Oggi" else day.format(race.startMillis).replaceFirstChar { it.uppercase() }) + " ${Espn.kickoff(race.startMillis)}"
        }
        val prefix = if (current == Section.EXPLORE) "Ferrari · " else ""
        setCarouselInfo("$prefix${race.grandPrix}", "${race.session} · $whenText · Nuvio · Formula 1")
    }

    override fun onRaceClicked(race: RaceSession) {
        if (race.state == "pre") {
            toast("${race.session} del ${race.grandPrix} in Nuvio dalle ${Espn.kickoff(race.startMillis)}")
            return
        }
        lifecycleScope.launch {
            val id = runCatching { SportsAddon.findRace(race) }.getOrNull()
            when {
                id == null -> toast("Sessione non ancora disponibile in Sports Streams, riprova tra poco")
                !apps.openNuvioContent(id, "sport") -> toast("Nuvio non è installato")
            }
        }
    }

    override fun onAppMenu(app: AppInfo) {
        if (app.pkg == Apps.HIDDEN_TILE) return
        val inDock = app.pkg in apps.dock(sportDock)
        val p = popup().title(app.label, if (inDock) (if (sportDock) "Nel dock Sport" else "Nel dock") else null)
        p.button("Apri") { onAppClicked(app) }
        if (inDock) {
            val pos = apps.dock(sportDock).indexOf(app.pkg)
            if (pos > 0) p.button("← Sposta a sinistra") { apps.moveInDock(app.pkg, -1, sportDock); refreshDock(app.label) }
            if (pos < apps.dock(sportDock).size - 1) p.button("Sposta a destra →") { apps.moveInDock(app.pkg, +1, sportDock); refreshDock(app.label) }
            p.button("Rimuovi dal dock") {
                apps.removeFromDock(app.pkg, sportDock)
                toast("${app.label} rimossa dal dock")
                refreshDock(app.label)
            }
        } else if (app.pkg != Apps.SETTINGS) {
            p.button(if (apps.dockFull(sportDock)) "Metti nel dock al posto di…" else "Aggiungi al dock") {
                if (!apps.dockFull(sportDock)) {
                    apps.addToDock(app.pkg, sport = sportDock)
                    toast("${app.label} aggiunta al dock")
                    refreshDock(app.label)
                } else replaceInDock(app)
            }
        }
        if (app.pkg != Apps.SETTINGS) p.button("Nascondi app") {
            apps.hide(app.pkg)
            toast("${app.label} nascosta · la trovi in \"App nascoste\"")
            rebuildExplore()
        }
        p.showSpotlight(page, appHero(app), 272, 153)
    }

    /** Dock full: pick which of the 5 apps [app] replaces. */
    private fun replaceInDock(app: AppInfo) {
        val p = popup().title("Dock pieno", "${app.label} al posto di…")
        apps.get(apps.dock(sportDock)).forEach { old ->
            p.button(old.label) {
                apps.addToDock(app.pkg, replacing = old.pkg, sport = sportDock)
                toast("${app.label} nel dock al posto di ${old.label}")
                refreshDock(app.label)
            }
        }
        p.showSpotlight(page, appHero(app), 272, 153)
    }

    /** The app's tile, enlarged, for the spotlight menu. */
    private fun appHero(app: AppInfo): View {
        val dp = resources.displayMetrics.density
        return android.widget.FrameLayout(this).apply {
            setBackgroundResource(R.drawable.card_bg)
            foreground = android.graphics.drawable.GradientDrawable().apply {
                setStroke((3 * dp).toInt(), 0xFFFFFFFF.toInt())
                cornerRadius = 16 * dp
            }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 16 * dp)
                }
            }
            elevation = 16 * dp
            if (app.banner != null) {
                addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageDrawable(app.banner)
                }, android.widget.FrameLayout.LayoutParams(-1, -1))
            } else {
                addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageDrawable(app.icon)
                    val pad = (28 * dp).toInt()
                    setPadding(pad, pad / 2, pad, pad * 2)
                }, android.widget.FrameLayout.LayoutParams(-1, -1))
                addView(TextView(context).apply {
                    text = app.label
                    textSize = 16f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(0xFFFFFFFF.toInt())
                }, android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM).apply { bottomMargin = (16 * dp).toInt() })
            }
        }
    }

    private fun showHiddenApps() {
        val hidden = apps.hiddenApps()
        if (hidden.isEmpty()) return
        val p = popup().title("App nascoste", "Premi OK per mostrarla di nuovo")
        hidden.forEach { app ->
            p.button(app.label) {
                apps.hidden = apps.hidden - app.pkg
                rebuildExplore()
            }
        }
        p.showSpotlight(page, appHero(AppInfo(Apps.HIDDEN_TILE, "App nascoste", null, getDrawable(android.R.drawable.ic_menu_view))), 272, 153)
    }

    private fun linkNuvio() {
        lifecycleScope.launch {
            val session = runCatching { nuvio.startLogin() }.getOrElse {
                toast("Nuvio non raggiungibile: ${it.message}")
                return@launch
            }
            val dp = resources.displayMetrics.density
            val qr = ImageView(this@MainActivity).apply {
                setImageBitmap(qrCode(session.webUrl, (220 * dp).toInt()))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val p = GlassPopup(this@MainActivity, widthDp = 380)
                .title("Collega Nuvio Sync", "Inquadra il QR col telefono e conferma")
                .view(qr, (220 * dp).toInt())
                .gap()
                .text("Usa il telefono dove hai già fatto l'accesso a Nuvio. Oppure apri: ${session.webUrl}")
            p.button("Annulla") {}
            p.showCentered(rowsView)
            val poll = launch {
                val deadline = System.currentTimeMillis() + 10 * 60_000L
                while (isActive && System.currentTimeMillis() < deadline) {
                    delay(session.interval * 1000L)
                    val status = runCatching { nuvio.pollLogin(session) }.getOrDefault("pending")
                    if (status == "approved" && nuvio.isLinked) {
                        p.dismiss()
                        toast("Nuvio collegato")
                        rebuildExplore()
                        return@launch
                    }
                    if (status == "expired" || status == "rejected") break
                }
                p.dismiss()
                toast("Codice scaduto, riprova")
            }
            p.onDismiss = { poll.cancel() }
        }
    }

    private fun qrCode(text: String, size: Int): android.graphics.Bitmap {
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        return android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
