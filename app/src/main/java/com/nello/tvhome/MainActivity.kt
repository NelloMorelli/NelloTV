package com.nello.tvhome

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
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
        const val ACTION_SCREENSAVER = "com.nello.tvhome.SCREENSAVER"
        const val ACTION_TEST_BANNER = "com.nello.tvhome.TEST_BANNER"
    }

    /** Order matches the tabs in the top bar. */
    private enum class Section { SEARCH, EXPLORE, MOVIES, SERIES, TV, SPORT }

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
    private var themeSig = ""
    private lateinit var catalogDots: CatalogDots
    private val appNamesSig by lazy { getSharedPreferences("app_names", MODE_PRIVATE).all.toString() }

    /**
     * The clock sits in the middle of the free space between the tabs and the right menu (not the
     * middle of the screen), and follows when the tabs change width (Cerca opening, hidden tabs).
     */
    private fun centerClock() {
        val clock = findViewById<View>(R.id.clockTop)
        val tabs = findViewById<View>(R.id.tabGroup)
        val bar = findViewById<View>(R.id.rightBar)
        val loc = IntArray(2)
        val glyphs = android.graphics.Rect()
        fun place() {
            if (clock.width == 0 || bar.width == 0) return
            // what the eye sees: the end of the last visible tab's text (not its padding)…
            val last = (0 until (tabs as ViewGroup).childCount).map { tabs.getChildAt(it) }.lastOrNull { it.visibility == View.VISIBLE } as? TextView ?: return
            last.getLocationInWindow(loc)
            val textRight = loc[0] + last.width - last.paddingEnd -
                ((last.width - last.paddingStart - last.paddingEnd) - last.paint.measureText(last.text.toString())) / 2f
            // …the pill's border…
            bar.getLocationInWindow(loc); val barLeft = loc[0]
            // …and the digits' ink, not the text box
            val tc = clock as TextView
            tc.paint.getTextBounds(tc.text.toString(), 0, tc.text.length, glyphs)
            val inkCenter = tc.paddingLeft + (glyphs.left + glyphs.right) / 2f
            (clock.parent as View).getLocationInWindow(loc); val parentLeft = loc[0]
            clock.translationX = (textRight + barLeft) / 2f - parentLeft - clock.left - inkCenter
        }
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
        tabs.addOnLayoutChangeListener(listener)
        bar.addOnLayoutChangeListener(listener)
        clock.addOnLayoutChangeListener(listener)
    }

    /** Personalizza: what's on screen, card size, hidden tabs. */
    private fun applyTheme() {
        val dp = resources.displayMetrics.density
        carousel.layoutParams = carousel.layoutParams.apply { height = (234 * dp * Theme.cardScale).toInt() }
        catalogDots.layoutParams = catalogDots.layoutParams.apply { height = (234 * dp * Theme.cardScale).toInt() }
        findViewById<View>(R.id.clockTop).visibility = if (Theme.showClock) View.VISIBLE else View.INVISIBLE
        val hidden = Customize.hiddenTabs(this)
        tabs.forEachIndexed { i, t -> t.visibility = if (Section.entries[i].name in hidden) View.GONE else View.VISIBLE }
    }

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
        Theme.load(this)
        themeSig = Theme.signature(this)
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
            findViewById(R.id.tabSeries), findViewById(R.id.tabTv), findViewById(R.id.tabSport),
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
        // the catalogue dots live in the space left of the first card
        catalogDots = CatalogDots(this)
        (carousel.parent as ViewGroup).let { parent ->
            val at = parent.indexOfChild(carousel)
            val lp = carousel.layoutParams
            parent.removeView(carousel)
            val frame = android.widget.FrameLayout(this).apply {
                clipChildren = false
                clipToPadding = false
                addView(carousel, android.widget.FrameLayout.LayoutParams(-1, lp.height))
                // same height as the carousel: the lit dot sits at the cards' middle
                addView(catalogDots, android.widget.FrameLayout.LayoutParams((24 * resources.displayMetrics.density).toInt(), lp.height).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    marginStart = (10 * resources.displayMetrics.density).toInt()
                })
            }
            parent.addView(frame, at, (lp as? ViewGroup.MarginLayoutParams)?.let { ViewGroup.MarginLayoutParams(it).apply { height = ViewGroup.LayoutParams.WRAP_CONTENT } } ?: ViewGroup.LayoutParams(-1, -2))
        }
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
        applyTheme()
        centerClock()
        setupSearch()
        setupTopBar()
        applyPrefs()
        tickClock()
        tickLiveScores()

        onBackPressedDispatcher.addCallback(this) { goTop() }

        // a new day since the launcher last ran: the first load already uses the new batch
        rollover()
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
                if (manual) toast(if (Updater.configured) "Nello TV è aggiornato (versione ${Updater.name()})" else "Aggiornamenti non configurati")
                return@launch
            }
            val seen = getSharedPreferences("update", MODE_PRIVATE)
            if (!manual && seen.getInt("offered", 0) == r.version) return@launch
            seen.edit().putInt("offered", r.version).apply()
            val p = GlassPopup(this@MainActivity, widthDp = 420)
                .title("Aggiornamento disponibile", "Versione ${r.name} · ora hai la ${Updater.name()}")
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
            for (section in listOf(Section.MOVIES, Section.SERIES, Section.TV, Section.SPORT)) {
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
        var next = current
        do { next = Section.entries[(next.ordinal + delta + Section.entries.size) % Section.entries.size] }
        while (tabs[next.ordinal].visibility != View.VISIBLE && next != current)
        show(next, focusContent = next != Section.SEARCH)
        if (next == Section.SEARCH) searchInput.requestFocus()
    }

    // ---------------------------------------------------------------- top bar

    private fun setupTopBar() {
        findViewById<FocusGroup>(R.id.barGroup).showSelected = false
        setupBarHandoff()
        findViewById<GlassView>(R.id.rightBar).apply { zoomOnFocus = false; frosted = true }
        findViewById<GlassView>(R.id.dock).frosted = true
        findViewById<GlassView>(R.id.searchGlass).apply { frosted = true; zoomOnFocus = false }
        findViewById<StatusIcon>(R.id.gearIcon).kind = StatusIcon.Kind.GEAR
        findViewById<StatusIcon>(R.id.vpnIcon).kind = StatusIcon.Kind.VPN
        findViewById<View>(R.id.settingsButton).setOnClickListener { showSettingsPopup() }
        findViewById<StatusIcon>(R.id.btIcon).kind = StatusIcon.Kind.SPEAKER
        findViewById<View>(R.id.btButton).setOnClickListener { showHeadphonesPopup() }
        // headphones icon follows the connection
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = updateHeadphonesIcon()
        }, android.content.IntentFilter(android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED))
        BluetoothAudio.withA2dp(this) { updateHeadphonesIcon() }
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

    /**
     * Tabs ⇄ top-right menu: the pill slides out of one bar's edge and, a beat later, slides into
     * the other from the facing edge — as if it passed behind the clock.
     */
    private fun setupBarHandoff() {
        val tabsG = findViewById<FocusGroup>(R.id.tabGroup)
        val barG = findViewById<FocusGroup>(R.id.barGroup)
        fun inside(v: View?, g: View): Boolean {
            var p: View? = v
            while (p != null) {
                if (p === g) return true
                p = p.parent as? View
            }
            return false
        }
        fun item(v: View, g: ViewGroup): View {
            var c = v
            while (c.parent !== g && c.parent is View) c = c.parent as View
            return c
        }
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            if (old == null || new == null) return@addOnGlobalFocusChangeListener
            when {
                inside(old, tabsG) && inside(new, barG) -> {
                    tabsG.slideOut(+1)
                    barG.slideIn(+1, item(new, barG), 200)
                }
                inside(old, barG) && inside(new, tabsG) -> {
                    barG.slideOut(-1)
                    tabsG.slideIn(-1, item(new, tabsG), 200)
                }
            }
        }
    }

    /** Settings: a full screen of their own, fading in over the home. */
    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun openConnectionDetails() = openPage(Intent(this, ConnectionActivity::class.java))

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
        val p = popup()
        val signal = details["Segnale"]?.substringBefore(" (")
        val ping = p.hero(
            when { !st.online -> "Offline"; st.ethernet -> "Ethernet"; else -> details["Rete"] ?: "Wi-Fi" },
            if (st.online) listOfNotNull("Connesso", signal).joinToString(" · ") else "Nessuna connessione a Internet",
        )
        val pingBase = ping.text
        // device status
        val (freeDisk, totalDisk) = DeviceStatus.storage()
        val (freeRam, totalRam) = DeviceStatus.memory(this)
        p.info("Memoria libera", "${DeviceStatus.gb(freeDisk)} di ${DeviceStatus.gb(totalDisk)}")
        p.info("RAM libera", "${DeviceStatus.gb(freeRam)} di ${DeviceStatus.gb(totalRam)}")
        p.gap()
        p.button("Dettagli e test velocità") { openConnectionDetails() }
        p.button("Impostazioni rete") { runCatching { startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) } }
        val job = lifecycleScope.launch { network.ping()?.let { ping.text = "$pingBase · $it ms" } }
        p.onDismiss = { job.cancel() }
        p.showUnder(findViewById(R.id.netButton))
    }

    /** 🔊 when the sound is on the TV, 🎧 when it's on headphones. */
    private fun updateHeadphonesIcon() {
        val on = BluetoothAudio.connected() != null
        findViewById<StatusIcon>(R.id.btIcon).apply {
            kind = if (on) StatusIcon.Kind.HEADPHONES else StatusIcon.Kind.SPEAKER
            level = if (on) 1 else 0
        }
    }

    /** 🔊 Audio: where the sound goes — the TV (HDMI) or one of the paired headphones. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun showHeadphonesPopup() {
        BluetoothAudio.withA2dp(this) {
            val now = BluetoothAudio.connected()
            val p = popup()
            p.hero(now?.let { BluetoothAudio.pretty(it) } ?: "TV", "L'audio esce da qui")
            p.button(if (now == null) "✓  TV" else "TV") {
                now?.let { d -> BluetoothAudio.disconnect(d); toast("Audio di nuovo sulla TV") }
            }
            BluetoothAudio.pairedAudio().forEach { d ->
                p.button(if (d == now) "✓  ${BluetoothAudio.pretty(d)}" else BluetoothAudio.pretty(d)) {
                    if (d == now) return@button
                    if (BluetoothAudio.connect(d)) toast("Collego ${BluetoothAudio.pretty(d)}… accendile se sono spente")
                    else toast("Impossibile collegare ${BluetoothAudio.pretty(d)}")
                }
            }
            p.gap()
            p.button("＋  Cerca nuove cuffie") { openPage(Intent(this, BluetoothActivity::class.java)) }
            p.button("Impostazioni audio") { runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } }
            p.showUnder(findViewById(R.id.btButton))
        }
    }

    private fun installedVpns() = LauncherPrefs.VPN_APPS.filter { (pkg, _) -> apps.get(listOf(pkg)).isNotEmpty() }

    private fun showVpnPopup() {
        val vpn = network.state.vpn
        val installed = installedVpns()
        val chosen = installed.firstOrNull { it.first == prefs.vpnPackage }
        val p = popup()
        val ip = p.hero(if (vpn) "Protetto" else "Non protetto", chosen?.second ?: "Nessuna app VPN scelta")
        val ipBase = ip.text
        when {
            installed.isEmpty() -> p.text("Nessuna app VPN installata.")
            chosen != null -> {
                p.button(if (vpn) "Apri ${chosen.second} per disattivarla" else "Apri ${chosen.second} per attivarla") { apps.launch(chosen.first) }
                p.button("Cambia app VPN") { chooseVpn() }
            }
            else -> p.button("Scegli l'app VPN") { chooseVpn() }
        }
        p.button("Dettagli connessione") { openConnectionDetails() }
        val job = lifecycleScope.launch { network.publicIp()?.let { ip.text = "$ipBase · IP $it" } }
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

    /**
     * ⚙: the launcher's state up top (version, whether it's up to date), then the two ways in —
     * the launcher's own settings and the Fire TV's.
     */
    private fun showSettingsPopup() {
        val p = popup()
        val status = p.hero("Nello TV", "Versione ${Updater.name()} · controllo aggiornamenti…")
        p.button("Impostazioni del launcher") { openSettings() }
        p.button("Impostazioni della Fire TV") { runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } }
        val job = lifecycleScope.launch {
            val r = if (Updater.configured) runCatching { Updater.check() }.getOrNull() else null
            status.text = "Versione ${Updater.name()} · " + (r?.let { "disponibile la ${it.name}" } ?: "aggiornato")
        }
        p.onDismiss = { job.cancel() }
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
            // the screensaver shows this exact background (wallpaper, no UI)
            withContext(Dispatchers.IO) {
                runCatching {
                    java.io.File(filesDir, ScreensaverView.BACKGROUND).outputStream().use { composed.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                }
            }
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
        // extra darkening chosen in Personalizza
        if (Theme.dim > 0f) c.drawColor(((Theme.dim * 255).toInt() shl 24))
        return out
    }

    private fun setGlass(source: android.graphics.Bitmap?) {
        lifecycleScope.launch {
            GlassView.blurred = source?.let { withContext(Dispatchers.Default) { GlassView.makeBlur(it) } }
            listOf(R.id.rightBar, R.id.dock, R.id.searchGlass, R.id.voiceButton).forEach { findViewById<View>(it).invalidate() }
        }
    }

    /**
     * Vertical navigation as one clear column:
     *   tabs  →  label (Oggi ▾ / ⟳ Altri per te)  →  carousel  →  dock  →  rows below
     * Up from the label goes to the current tab (not the top-right menu); down from the tabs
     * goes straight to the carousel's wide card; Back from anywhere goes to the current tab.
     */
    // ---------------------------------------------------------------- screensaver

    private var saver: ScreensaverView? = null
    private var saverEnding = false
    private val idleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val idleRunnable = Runnable { startScreensaver() }

    /** A few seconds before the system's own screensaver would kick in, ours starts. */
    private fun resetIdle() {
        idleHandler.removeCallbacks(idleRunnable)
        if (!prefs.screensaver || !inForeground) return
        val timeout = runCatching { android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT) }.getOrDefault(300_000)
        idleHandler.postDelayed(idleRunnable, (timeout - 8_000L).coerceAtLeast(10_000L))
    }

    /** Everything but the wallpaper melts away; then the wallpaper dims and the clock appears. */
    fun startScreensaver() {
        if (saver != null || !inForeground) return
        // our screensaver holds the screen, so Amazon's never starts while it's up
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val v = ScreensaverView(this, withWallpaper = false)
        (findViewById<ViewGroup>(android.R.id.content)).addView(v, ViewGroup.LayoutParams(-1, -1))
        saver = v
        page.animate().cancel()
        page.animate().alpha(0f).setDuration(1800).setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)).start()
        v.start {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WakeService.instance?.sleep()
        }
    }

    /** Any key: the clock fades, the dimming lifts, and the UI comes back. */
    private fun stopScreensaver() {
        val v = saver ?: return
        if (saverEnding) return
        saverEnding = true
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        v.end(900) {
            (v.parent as? ViewGroup)?.removeView(v)
            saver = null
            saverEnding = false
        }
        page.animate().alpha(1f).setStartDelay(250).setDuration(900).setInterpolator(android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f)).start()
        resetIdle()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // screensaver up: the key only wakes the UI
        if (saver != null) {
            if (event.action == android.view.KeyEvent.ACTION_UP) stopScreensaver()
            return true
        }
        if (event.action == android.view.KeyEvent.ACTION_DOWN) resetIdle()
        if (event.action == android.view.KeyEvent.ACTION_DOWN) userMoved = true
        if (scrollsCatalogs(current) && carousel.hasFocus() &&
            (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP || event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN)) {
            // holding the key runs through the catalogues (one per slide), never past the edges
            if (event.action == android.view.KeyEvent.ACTION_DOWN)
                onCatalogKey(if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) -1 else 1, held = event.repeatCount > 0)
            return true
        }
        if (event.action == android.view.KeyEvent.ACTION_DOWN && isSingle(current)) {
            val tabsFocused = findViewById<View>(R.id.tabGroup).hasFocus()
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP -> when {
                    carousel.hasFocus() && catalogLabel.isShown -> { catalogLabel.requestFocus(); return true }
                    catalogLabel.isFocused || (carousel.hasFocus() && !catalogLabel.isShown) -> {
                        tabs[current.ordinal].requestFocus(); return true
                    }
                }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> when {
                    tabsFocused -> {
                        // switch to the highlighted tab right away (no waiting) and go into it
                        tabJob?.cancel()
                        val target = Section.entries.getOrNull(tabs.indexOfFirst { it.isFocused }) ?: current
                        if (target == Section.SEARCH) {
                            if (current != Section.SEARCH) show(Section.SEARCH)
                            searchInput.requestFocus()
                        } else {
                            if (target != current) show(target, focusContent = true) else focusFirstCard()
                        }
                        return true
                    }
                    catalogLabel.isFocused -> { focusFirstCard(); return true }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** carousel card to reopen per tab (Torna dove eri) */
    private val pendingCard = HashMap<Section, Int>()
    private var inForeground = false

    override fun onPause() {
        super.onPause()
        inForeground = false
        idleHandler.removeCallbacks(idleRunnable)
        if (saver != null) {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            (saver?.parent as? ViewGroup)?.removeView(saver)
            saver = null
            saverEnding = false
            page.alpha = 1f
        }
        getSharedPreferences("where", MODE_PRIVATE).edit()
            .putString("section", current.name)
            .putInt("card", carouselAdapter?.active ?: 0)
            .putLong("at", System.currentTimeMillis())
            .apply()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already home: back to the top of Esplora
        if (intent.action == ACTION_TEST_BANNER) { WakeService.testBanner(); return }
        if (intent.action == ACTION_SCREENSAVER) {
            idleHandler.postDelayed({ startScreensaver() }, 600)
            return
        }
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
        // missed 3:00 (asleep or off): the new suggestions come now
        dailyRefresh()
        scheduleNightly()
        // back from Sportio: the VPN we switched on goes off again
        Sportio.wireguardDownIfOurs(this)
        // first-start setup not finished (new Fire TV, or Home pressed during it): it comes first
        if (!prefs.onboarded) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            overridePendingTransition(0, 0)
        }
        // something changed in Personalizza: rebuild the home with it
        if (Theme.signature(this) != themeSig || appNamesSig != getSharedPreferences("app_names", MODE_PRIVATE).all.toString()) {
            recreate()
            return
        }
        inForeground = true
        ensureBackgroundService()
        // ours rides on Amazon's screensaver (it covers it): it must be on, else the TV just sleeps
        runCatching { android.provider.Settings.Secure.putInt(contentResolver, "screensaver_enabled", if (prefs.screensaver) 1 else 0) }
        resetIdle()
        applyPrefs()
        network.refresh()
        refreshExploreLiveRows()
    }

    // ---------------------------------------------------------------- sections

    private fun specs(section: Section): List<Spec> {
        val hidden = Customize.hiddenCatalogs(this)
        return allSpecs(section).filter { it.key !in hidden }
    }

    private fun allSpecs(section: Section): List<Spec> = when (section) {
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
        Section.TV -> listOf(
            Spec("tv_now", "In onda ora", RowKind.MIXED, mixed = { TvGuide.onNow(TvGuide.shows(this)) }),
            Spec("tv_prime", "Stasera", RowKind.MIXED, mixed = { TvGuide.primeTime(TvGuide.shows(this)) }),
            Spec("tv_sky_cinema", "Sky Cinema · in onda", RowKind.MIXED, mixed = { TvGuide.onNow(TvGuide.shows(this), TvGuide.skyCinema) }),
            Spec("tv_sky_ent", "Sky Intrattenimento · in onda", RowKind.MIXED, mixed = { TvGuide.onNow(TvGuide.shows(this), TvGuide.skyEntertainment) }),
            Spec("tv_sky_prime", "Sky · stasera", RowKind.MIXED, mixed = { TvGuide.primeTime(TvGuide.shows(this), TvGuide.skyCinema + TvGuide.skyEntertainment) }),
        )
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
            Spec("sport_apps", "App sport", RowKind.APPS, apps = { apps.get(apps.sportApps) }),
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
        // "In onda ora" moves on every few minutes
        Section.TV -> 5 * 60_000L
        else -> 30 * 60_000L
    }

    private var tabAnimGen = 0

    /** What slides when the tab changes: the content, not the header; the dock only if it changes. */
    private fun tabContent(section: Section, dockChanges: Boolean): List<View> =
        if (isSingle(section)) listOfNotNull(catalogLabel, carousel, carouselTitle, carouselMeta, below, findViewById<View>(R.id.dock).takeIf { dockChanges })
        else listOf(hero, rowsView)

    /**
     * The current tab is full white, the others dimmed — colour only, no bold (bold changed the
     * label's width and made the row jump). The colour change is a short crossfade.
     */
    private fun styleTabs(section: Section) {
        tabs.forEachIndexed { i, t ->
            val sel = i == section.ordinal
            t.isSelected = sel
            if (t.typeface?.isBold == true || t.tag == null) {
                t.typeface = Fonts.medium
                t.tag = "styled"
            }
            val to = if (sel) 0xFFFFFFFF.toInt() else 0x99FFFFFF.toInt()
            val from = t.currentTextColor
            if (from != to) android.animation.ValueAnimator.ofObject(android.animation.ArgbEvaluator(), from, to).apply {
                duration = 480
                addUpdateListener { a -> t.setTextColor(a.animatedValue as Int) }
                start()
            }
        }
        findViewById<FocusGroup>(R.id.tabGroup).refresh()
    }

    /**
     * Change tab with a directional slide: the old content slides and fades out towards where
     * you're going, the new one comes in from the other side. Header stays put; the dock only
     * crossfades when entering/leaving Sport (its own dock).
     */
    private fun show(section: Section, focusContent: Boolean = false) {
        val from = current
        if (from == section || rowsView.tag == null) {
            showNow(section, focusContent)
            return
        }
        val gen = ++tabAnimGen
        styleTabs(section)
        val dir = if (section.ordinal > from.ordinal) 1 else -1
        val shift = 56 * resources.displayMetrics.density
        val dockChanges = (from == Section.SPORT) != (section == Section.SPORT)
        val out = tabContent(from, dockChanges).filter { it.visibility == View.VISIBLE }
        if (out.isEmpty()) {
            showNow(section, focusContent)
            return
        }
        // durations are doubled: the Fire TV runs animations at 0.5x
        out.forEachIndexed { i, v ->
            v.animate().cancel()
            v.animate().alpha(0f).translationX(-dir * shift).setDuration(240)
                .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f))
                .withEndAction(if (i != 0) null else Runnable {
                    if (gen != tabAnimGen) return@Runnable
                    out.forEach { it.translationX = 0f }
                    showNow(section, focusContent)
                    tabContent(section, dockChanges).forEach { inV ->
                        inV.animate().cancel()
                        inV.alpha = 0f
                        inV.translationX = dir * shift
                        inV.animate().alpha(1f).translationX(0f).setDuration(460)
                            .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).start()
                    }
                })
                .start()
        }
    }

    /** Download the first cards' images into the cache (poster and wide image, at card size). */
    private fun prefetch(items: List<Any>) {
        val loader = coil.Coil.imageLoader(this)
        val dp = resources.displayMetrics.density
        items.take(8).forEach { item ->
            val urls = when (item) {
                is MediaItem -> listOfNotNull(item.poster to CarouselAdapter.POSTER_W, item.image to CarouselAdapter.WIDE_W)
                else -> emptyList()
            }
            urls.forEach { (url, w) ->
                if (url != null) loader.enqueue(
                    coil.request.ImageRequest.Builder(this).data(url)
                        .size((w * dp).toInt(), (CarouselAdapter.HEIGHT * dp).toInt()).build()
                )
            }
        }
    }

    private fun showNow(section: Section, focusContent: Boolean = false) {
        val changed = current != section || rowsView.tag == null
        current = section
        styleTabs(section)
        updateSearchTab()
        refreshDock()
        val singleMode = isSingle(section)
        val infoUnder = if (Theme.showCardInfo && (section == Section.EXPLORE || section == Section.SPORT || section == Section.TV)) View.VISIBLE else View.GONE
        carouselTitle.visibility = infoUnder
        carouselMeta.visibility = infoUnder
        below.visibility = if (hasBelow(section)) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.bentoTitle).text = if (section == Section.SPORT) "App sport" else "Tutte le app"
        if (changed) { shownContinue = null; shownBento = null }
        if (changed) page.scrollTo(0, 0)
        single.visibility = if (singleMode) View.VISIBLE else View.GONE
        findViewById<View>(R.id.dock).visibility = if (Theme.showDock) View.VISIBLE else View.GONE
        if (tabAnimGen == 0 || !changed) listOf(single, rowsView, hero).forEach { it.alpha = 1f }
        hero.visibility = if (singleMode) View.GONE else View.VISIBLE
        rowsView.visibility = if (singleMode) View.GONE else View.VISIBLE
        // Sport keeps its big clock; elsewhere the clock sits in the header
        if (changed) rowsView.tag = section
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
                // the carousel's first catalog: fetch its images now, so the tab slides in complete
                if (i == 0 && isSingle(state.section)) prefetch(itemsOf(state.rows[i]))
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
        // direct jump: smoothScrollToPosition(0) moved this list the wrong way
        (rowsView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
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
        val voiceIcon = findViewById<StatusIcon>(R.id.voiceIcon).apply { kind = StatusIcon.Kind.MIC }
        findViewById<GlassView>(R.id.voiceButton).apply {
            frosted = true
            solidFocus = true
            zoomOnFocus = false
            // focused: white circle, dark mic (like the top-right icons)
            setOnFocusChangeListener { _, has -> voiceIcon.ink = if (has) 1f else 0f }
            setOnClickListener { startVoiceSearch() }
        }
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
        if (current == Section.SEARCH) {
            adapter.submit(searchRows)
            // new results: start from the first row (it was left scrolled off the top)
            rowsView.scrollToPosition(0)
            rowsView.post { (rowsView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)?.scrollToPositionWithOffset(0, 0) }
        }
    }

    // ---------------------------------------------------------------- single screen

    /** "Per te": today's Napoli matches and Ferrari's F1 sessions, then films and series alternated. */
    private suspend fun perTe(): List<Any> = kotlinx.coroutines.coroutineScope {
        val napoli = async { runCatching { Espn.teamToday(prefs.teamId) }.getOrElse { emptyList() }.filter { it.state != "post" } }
        val f1 = async { if (prefs.f1Team == null) emptyList() else runCatching { Espn.f1Today() }.getOrElse { emptyList() }.filter { it.state != "post" } }
        val picks = async { runCatching { personalPicks() }.getOrElse { emptyList() } }
        val movies = async {
            picks.await().filter { it.type == "movie" }.ifEmpty { runCatching { Tmdb.list("/trending/movie/week", "movie", page = perTePage + 1) }.getOrElse { emptyList() } }
        }
        val series = async {
            picks.await().filter { it.type == "series" }.ifEmpty { runCatching { Tmdb.list("/trending/tv/week", "series", page = perTePage + 1) }.getOrElse { emptyList() } }
        }
        // tonight's best film on Sky Cinema (by TMDB rating), until it ends
        val tonight = async {
            runCatching {
                TvGuide.primeTime(TvGuide.shows(this@MainActivity), TvGuide.skyCinema)
                    .filter { it.stop > System.currentTimeMillis() }
                    .map { it to Tmdb.voteForTitle(it.title) }
                    .maxByOrNull { it.second }?.first
            }.getOrNull()
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
        sport + listOfNotNull(tonight.await()) + mixed
    }

    /**
     * Suggestions from your Nuvio history: TMDB recommendations for what you're watching and what's in
     * your library, most-recommended first, without what you already have.
     */
    // ---------------------------------------------------------------- daily "Per te"
    // A new batch of suggestions every day at 3:00. If the Fire TV was off or asleep then, it
    // happens as soon as the launcher is back (the "day" of suggestions starts at 3:00).

    private val perTePrefs by lazy { getSharedPreferences("perte", MODE_PRIVATE) }

    /** which batch of "Per te" is shown; bumped by the refresh button and every night */
    private var perTePage: Int
        get() = perTePrefs.getInt("page", 0)
        set(v) = perTePrefs.edit().putInt("page", v).apply()

    private val rome = java.util.TimeZone.getTimeZone("Europe/Rome")

    /** 23 October from 3:00 to 24 October 2:59 is one suggestion day */
    private fun suggestionDay(): Long {
        val c = java.util.Calendar.getInstance(rome)
        c.add(java.util.Calendar.HOUR_OF_DAY, -3)
        return c.get(java.util.Calendar.YEAR) * 1000L + c.get(java.util.Calendar.DAY_OF_YEAR)
    }

    private val nightly = Runnable { dailyRefresh() ; scheduleNightly() }

    private fun scheduleNightly() {
        val next = java.util.Calendar.getInstance(rome).apply {
            set(java.util.Calendar.HOUR_OF_DAY, 3); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 5)
            if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        idleHandler.removeCallbacks(nightly)
        idleHandler.postDelayed(nightly, next.timeInMillis - System.currentTimeMillis())
    }

    /** New day of suggestions: next batch, Esplora reloaded (in the background if you're elsewhere). */
    /** True if a new suggestion day has started (the next batch is chosen). */
    private fun rollover(): Boolean {
        val today = suggestionDay()
        val last = perTePrefs.getLong("day", 0)
        if (last == today) return false
        perTePrefs.edit().putLong("day", today).apply()
        if (last == 0L) return false
        perTePage = perTePage + 1
        return true
    }

    private fun dailyRefresh() {
        if (!rollover()) return
        // reload Esplora now: by morning its images are already on disk
        states[Section.EXPLORE]?.let { it.loadedAt = 0 }
        val state = SectionState(Section.EXPLORE, specs(Section.EXPLORE))
        states[Section.EXPLORE] = state
        load(state)
    }
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
                    val verb = if (seed.tmdbId in inProgress) "hai guardato" else "hai in libreria"
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
            foreground = Theme.cardFocus(this@MainActivity)
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
    // ---------------------------------------------------------------- vertical catalogs
    // Film, Serie TV and TV: the catalogues are a vertical list. ↑/↓ move to the previous/next one;
    // ↑ on the first goes up to the top menu, ↓ on the last down to the dock. Back: top menu.

    private fun scrollsCatalogs(s: Section) = s == Section.MOVIES || s == Section.SERIES || s == Section.TV

    private var catalogAnimating = false
    private var dotsAnimate = false

    private fun onCatalogKey(dir: Int, held: Boolean) {
        val state = states[current] ?: return
        val list = scrollable(state)
        val at = list.indexOf(catalogIndex[current] ?: 0).coerceAtLeast(0)
        val next = at + dir
        when {
            next in list.indices -> cycleCatalog(dir)
            // past the edges: leave the list (not while holding, so a held key stops at the end)
            held -> {}
            dir < 0 -> tabs[current.ordinal].requestFocus()
            else -> dockList.getChildAt(0)?.requestFocus()
        }
    }

    /** Catalogs you can scroll to: loaded and not empty. */
    private fun scrollable(state: SectionState) =
        state.rows.indices.filter { state.ready[it] && state.rows[it].size > 0 && state.rows[it].kind != RowKind.APPS }

    private fun cycleCatalog(dir: Int) {
        val state = states[current] ?: return
        val list = scrollable(state)
        if (list.size < 2 || catalogAnimating) return
        val at = list.indexOf(catalogIndex[current] ?: 0).coerceAtLeast(0)
        val next = list.getOrNull(at + dir) ?: return
        val d = 48 * resources.displayMetrics.density
        val views = listOf(catalogLabel, carousel, carouselTitle, carouselMeta)
        val out = android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f)
        val inn = android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f)
        catalogAnimating = true
        // the highlight glides to the new catalogue right away, with the carousel's slide
        catalogDots.set(list.size, list.indexOf(next), animate = true)
        // the current catalog slides away in the direction you pressed, the next one comes from the other side
        views.forEachIndexed { i, v ->
            v.animate().cancel()
            v.animate().translationY(-dir * d).alpha(0f).setStartDelay(0).setDuration(280).setInterpolator(out)
                .withEndAction(if (i != 0) null else Runnable {
                    catalogIndex[current] = next
                    shownCatalog = null
                    bindSingle(state)
                    focusFirstCard()
                    views.forEachIndexed { j, w ->
                        w.translationY = dir * d
                        w.animate().translationY(0f).alpha(1f).setStartDelay(j * 30L).setDuration(620).setInterpolator(inn)
                            .withEndAction(if (j == views.size - 1) Runnable { catalogAnimating = false } else null).start()
                    }
                }).start()
        }
    }

    /** the catalogue's name (where it is among the others: the dots) */
    private fun catalogLabelText(state: SectionState, index: Int): CharSequence = state.rows[index].title

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
        catalogLabel.text = when {
            explore -> if (refreshing) "Aggiorno…" else "⟳  Per te"
            scrollsCatalogs(state.section) -> catalogLabelText(state, index)
            else -> "${row.title}  ▾"
        }
        // where ↑/↓ scroll the catalogs the label is only a title, not a button
        catalogLabel.isFocusable = !scrollsCatalogs(state.section)
        val list = scrollable(state)
        catalogDots.visibility = if (scrollsCatalogs(state.section) && list.size > 1) View.VISIBLE else View.GONE
        catalogDots.set(list.size, list.indexOf(index).coerceAtLeast(0), animate = dotsAnimate)
        dotsAnimate = false
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
            is TvShow -> onTvFocused(head)
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
            is TvShow -> onTvFocused(item)
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
            prefs.teamName?.let { t -> p.button("Calendario $t") { openSportPage(SportPagesActivity.NAPOLI) } }
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
        // Per te: just the poster and why it's there
        else if (current == Section.EXPLORE) setCarouselInfo(item.reason.orEmpty(), "")
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

    private var focusedMatch: String? = null

    override fun onMatchFocused(match: Match) {
        if (isSingle(current)) {
            focusedMatch = match.id
            val whenText = when (match.state) {
                "in" -> "In diretta · ${match.detail}"
                "post" -> "Finale ${match.homeScore ?: 0}-${match.awayScore ?: 0}"
                else -> {
                    val isToday = Espn.dayLabel(match) == SimpleDateFormat("EEE d MMM", Locale.ITALY).format(Date())
                    (if (isToday) "Oggi" else Espn.dayLabel(match).replaceFirstChar { it.uppercase() }) + " ${Espn.kickoff(match)}"
                }
            }
            setCarouselInfo("${match.home} – ${match.away}", listOfNotNull(whenText, match.league.label).joinToString(" · "))
            // then the channel it's on, once the guide has answered
            if (match.state != "post") lifecycleScope.launch {
                val channel = Sportio.channelFor(this@MainActivity, match)
                if (focusedMatch == match.id)
                    setCarouselInfo("${match.home} – ${match.away}", listOf(whenText, channel, match.league.label).joinToString(" · "))
            }
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
            val action = if (match.state == "post") "Partita terminata · OK per i dettagli"
                else "Su ${Sportio.channelFor(this@MainActivity, match)} · OK per guardarla in Sportio"
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

    /** OK on a match: Sportio, on the channel showing it. Finished matches open their page. */
    override fun onMatchClicked(match: Match) {
        if (match.state == "post") { onMatchDetails(match); return }
        lifecycleScope.launch {
            val channel = Sportio.channelFor(this@MainActivity, match)
            openOnSportio(channel, "${match.home} – ${match.away}")
        }
    }

    /** Sportio on the channel (even before kick-off), with WireGuard switched on first. */
    private fun openOnSportio(channel: String, what: String) {
        if (!Sportio.installed(this)) { toast("Sportio non è installato"); return }
        // no WireGuard on this Fire TV: just open the channel
        val tunnel = prefs.sportVpn?.takeIf { Sportio.wireguardInstalled(this) }
        if (tunnel == null) {
            toast("$what su $channel")
            Sportio.play(this, channel)
            return
        }
        if (checkSelfPermission(Sportio.WG_PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Sportio.WG_PERMISSION), 7)
            toast("Consenti a Nello TV di attivare WireGuard, poi riprova")
            return
        }
        toast("$what su $channel · attivo WireGuard…")
        lifecycleScope.launch {
            if (!Sportio.wireguardUp(this@MainActivity, tunnel)) toast("WireGuard non si è attivato, apro comunque")
            Sportio.play(this@MainActivity, channel)
        }
    }

    override fun onMatchDetails(match: Match) {
        openPage(Intent(this, SportPagesActivity::class.java)
            .putExtra(SportPagesActivity.EXTRA_PAGE, SportPagesActivity.MATCH)
            .putExtra(SportPagesActivity.EXTRA_EVENT, match.id)
            .putExtra(SportPagesActivity.EXTRA_LEAGUE, match.league.espn))
    }

    private fun openSportPage(page: String) =
        openPage(Intent(this, SportPagesActivity::class.java).putExtra(SportPagesActivity.EXTRA_PAGE, page))

    override fun onTvFocused(show: TvShow) {
        if (current == Section.EXPLORE) {
            setCarouselInfo("Stasera in TV · ${show.channel.name} alle ${TvGuide.time(show.start)}", "")
            return
        }
        setCarouselInfo("${show.channel.name} · ${show.title}",
            listOfNotNull("${TvGuide.time(show.start)} – ${TvGuide.time(show.stop)}", show.category, if (show.isOn) "In onda" else null).joinToString(" · "))
    }

    /** OK on a programme: the channel's app (RaiPlay, Mediaset Infinity, or NeroZone for the rest). */
    override fun onTvClicked(show: TvShow) {
        // Sky: straight onto the channel in Sportio (with WireGuard, like sport)
        show.channel.sportio?.let { openOnSportio(it, "${show.channel.name} · ${show.title}"); return }
        if (!apps.launch(show.channel.app)) toast("App per ${show.channel.name} non installata")
    }

    override fun onRaceFocused(race: RaceSession) {
        val day = SimpleDateFormat("EEE d MMM", Locale.ITALY).apply { timeZone = java.util.TimeZone.getTimeZone("Europe/Rome") }
        val isToday = day.format(race.startMillis) == day.format(System.currentTimeMillis())
        val whenText = when (race.state) {
            "in" -> "In diretta"
            "post" -> "Conclusa"
            else -> (if (isToday) "Oggi" else day.format(race.startMillis).replaceFirstChar { it.uppercase() }) + " ${Espn.kickoff(race.startMillis)}"
        }
        val prefix = if (current == Section.EXPLORE) prefs.f1Team?.let { "$it · " }.orEmpty() else ""
        setCarouselInfo("$prefix${race.grandPrix}", "${race.session} · $whenText · ${Sportio.channelFor(race)} · Formula 1")
    }

    override fun onRaceClicked(race: RaceSession) =
        openOnSportio(Sportio.channelFor(race), "${race.session} · ${race.grandPrix}")

    /** the tile the spotlight flies out of (and back into) */
    private var spotlightSource: View? = null

    override fun onAppMenu(app: AppInfo) {
        if (app.pkg == Apps.HIDDEN_TILE) return
        spotlightSource = currentFocus
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
        // the Sport tab's app grid, editable from anywhere
        if (app.pkg != Apps.SETTINGS) {
            val inSport = app.pkg in apps.sportApps
            p.button(if (inSport) "Rimuovi da App sport" else "Aggiungi ad App sport") {
                val gridIndex = bentoTiles().indexOfFirst { it.contentDescription == app.label }
                apps.sportApps = if (inSport) apps.sportApps - app.pkg else apps.sportApps + app.pkg
                toast(if (inSport) "${app.label} rimossa da App sport" else "${app.label} aggiunta ad App sport")
                refreshSportApps(keepFocusAt = gridIndex)
            }
        }
        if (app.pkg != Apps.SETTINGS) p.button("Nascondi app") {
            apps.hide(app.pkg)
            toast("${app.label} nascosta · la trovi in \"App nascoste\"")
            rebuildExplore()
        }
        p.showSpotlight(page, appHero(app), 272, 153, spotlightSource)
    }

    /** Update the Sport tab's "App sport" grid in place. */
    private fun bentoTiles(): List<View> =
        (0 until bento.childCount).flatMap { r -> (bento.getChildAt(r) as android.view.ViewGroup).let { row -> (0 until row.childCount).map { row.getChildAt(it) } } }

    private fun refreshSportApps(keepFocusAt: Int = -1) {
        val state = states[Section.SPORT] ?: return
        val row = state.rows.firstOrNull { it.key == "sport_apps" } ?: return
        row.apps = apps.get(apps.sportApps)
        if (current == Section.SPORT) {
            shownBento = null
            bindBelow(state)
            // the grid was rebuilt: stay on the same spot (the next app, or the previous one at the end)
            if (keepFocusAt >= 0) bento.postDelayed({
                bentoTiles().let { t -> t.getOrNull(keepFocusAt.coerceAtMost(t.size - 1))?.requestFocus() }
            }, 150)
        }
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
        p.showSpotlight(page, appHero(app), 272, 153, spotlightSource)
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
