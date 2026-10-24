package com.nello.tvhome

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import java.text.Collator
import java.util.Locale

class Apps(private val context: Context) {
    private val pm: PackageManager = context.packageManager
    private val prefs = context.getSharedPreferences("apps", Context.MODE_PRIVATE)

    companion object {
        const val NUVIO = "com.nuvio.tv"
        const val SETTINGS = "settings"
        /** pseudo-app at the end of "Tutte le app" that lists the hidden ones */
        const val HIDDEN_TILE = "hidden_apps"

        /** the dock: at most [DOCK_MAX] apps */
        const val DOCK_MAX = 5
        val DEFAULT_SPORT_APPS = listOf(
            "com.dazn", "com.amazon.firebat", "it.mediaset.infinitytv1",
            // NeroZone, sport and IPTV apps
            "iptv.apupl", "com.sportzx.live", "com.vegetatv.app",
            "ar.tvplayer.tv", "com.nst.iptvsmarterstvbox", "com.iptvstreamsolutions.iptvplus",
            "com.myiptvonline.implayer", "studio.scillarium.ottnavigator",
        )
        val DEFAULT_SPORT_DOCK = listOf(
            "com.dazn",
            "iptv.apupl",
            "com.amazon.firebat",
            "ar.tvplayer.tv",
            "com.amazon.firetv.youtube",
        )
        val DEFAULT_FAVOURITES = listOf(
            NUVIO,
            "com.netflix.ninja",
            "com.amazon.firebat",
            "iptv.apupl",
            "com.amazon.firetv.youtube",
        )

        /** apps shown at the top of the Film and Serie TV tabs */
        val VIDEO_APPS = listOf(
            NUVIO,
            "com.netflix.ninja",
            "com.amazon.avod",
            "com.amazon.avod.thirdpartyclient",
            "com.hbo.hbonow",
            "com.apple.atve.amazon.appletv",
            "com.disney.disneyplus",
            "it.rainet.androidcr",
            "it.mediaset.infinitytv1",
            "tv.pluto.android",
        )
    }

    private var cache: Map<String, AppInfo>? = null

    /** Every visible app, alphabetical. */
    fun all(): List<AppInfo> = installed().values
        .filter { it.pkg !in hidden }
        .sortedWith(compareBy(Collator.getInstance(Locale.ITALY)) { it.label })

    /**
     * "Tutte le app": every visible app, with the ones in a folder replaced by the folder's tile
     * (alphabetical by its name), plus the "App nascoste" tile when something is hidden.
     */
    fun grid(): List<AppInfo> {
        val folders = Collections(context).folders
        val inFolders = folders.flatMap { it.apps }.toSet()
        val tiles = all().filter { it.pkg !in inFolders } + folders.mapNotNull { f ->
            val members = get(f.apps)
            if (members.isEmpty()) null else AppInfo(Collections.FOLDER + f.id, f.name, null, folderIcon(members))
        }
        val az = tiles.sortedWith(compareBy(Collator.getInstance(Locale.ITALY)) { it.label })
        val sorted = when (gridOrder) {
            "used" -> az.sortedByDescending { t ->
                // a folder counts as much as its most used app
                if (t.pkg.startsWith(Collections.FOLDER)) folders.firstOrNull { Collections.FOLDER + it.id == t.pkg }?.apps?.maxOfOrNull { uses(it) } ?: 0 else uses(t.pkg)
            }
            "custom" -> {
                val order = gridCustom
                az.sortedBy { t -> order.indexOf(t.pkg).let { if (it < 0) Int.MAX_VALUE else it } }
            }
            else -> az
        }
        val count = hidden.count { it in installed() }
        return if (count == 0) sorted
        else sorted + AppInfo(HIDDEN_TILE, "App nascoste ($count)", null, context.getDrawable(android.R.drawable.ic_menu_view))
    }

    /** A folder's face: the first four apps' icons in a 2×2 grid. */
    private fun folderIcon(members: List<AppInfo>): android.graphics.drawable.Drawable {
        val size = 256
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val shown = members.take(4)
        // 1 app: large in the middle; 2: side by side; 3–4: a 2×2 grid
        val cells: List<android.graphics.Rect> = when (shown.size) {
            1 -> listOf(android.graphics.Rect(48, 48, 208, 208))
            2 -> listOf(android.graphics.Rect(8, 68, 124, 184), android.graphics.Rect(132, 68, 248, 184))
            else -> (0 until shown.size).map { i ->
                val x = (i % 2) * 128; val y = (i / 2) * 128
                android.graphics.Rect(x + 12, y + 12, x + 116, y + 116)
            }
        }
        shown.forEachIndexed { i, app ->
            val d = (app.icon ?: app.banner ?: return@forEachIndexed).constantState?.newDrawable()?.mutate() ?: return@forEachIndexed
            d.bounds = cells[i]
            d.draw(c)
        }
        return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
    }

    fun get(pkgs: List<String>): List<AppInfo> = installed().let { map -> pkgs.filter { it !in hidden }.mapNotNull { map[it] } }

    fun hiddenApps(): List<AppInfo> = installed().let { map -> hidden.mapNotNull { map[it] } }
        .sortedWith(compareBy(Collator.getInstance(Locale.ITALY)) { it.label })

    fun invalidate() {
        cache = null
    }

    private fun installed(): Map<String, AppInfo> = cache ?: load().also { cache = it }

    private val names = context.getSharedPreferences("app_names", Context.MODE_PRIVATE)

    private fun load(): Map<String, AppInfo> {
        val result = linkedMapOf<String, AppInfo>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName || pkg in result) continue
                val ai = ri.activityInfo
                val custom = imageFile(pkg).takeIf { it.exists() }?.let {
                    android.graphics.BitmapFactory.decodeFile(it.path)?.let { b -> android.graphics.drawable.BitmapDrawable(context.resources, b) }
                }
                result[pkg] = AppInfo(
                    pkg = pkg,
                    // a name chosen in Personalizza wins
                    label = names.getString(pkg, null) ?: ri.loadLabel(pm).toString(),
                    // your own image wins over the app's banner
                    banner = custom ?: ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm),
                    icon = ri.loadIcon(pm),
                )
            }
        }
        result[SETTINGS] = AppInfo(SETTINGS, "Impostazioni", null, context.getDrawable(android.R.drawable.ic_menu_manage))
        return result
    }

    fun launch(pkg: String): Boolean {
        val intent = if (pkg == SETTINGS) {
            Intent(Settings.ACTION_SETTINGS)
        } else {
            pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        }
        return start(intent, pkg)
    }

    /** Opens the title page in Nuvio. A fresh task makes Nuvio honour the link even when it is already open. */
    fun openInNuvio(item: MediaItem, imdbId: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nuvio://detail/${item.type}/$imdbId"))
            .setPackage(NUVIO)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return start(intent, NUVIO)
    }

    /** Opens any addon item (e.g. a Sports Streams match) on its Nuvio page; these ids aren't accepted as nuvio:// links. */
    fun openNuvioContent(contentId: String, contentType: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN)
            .setClassName(NUVIO, "$NUVIO.MainActivity")
            .putExtra("contentId", contentId)
            .putExtra("contentType", contentType)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return start(intent, NUVIO)
    }

    /** Resumes a title: straight to Nuvio's source list for that movie or episode, skipping the detail page. */
    fun resumeInNuvio(item: MediaItem, imdbId: String, videoId: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN)
            .setClassName(NUVIO, "$NUVIO.MainActivity")
            .putExtra("contentId", imdbId)
            .putExtra("contentType", item.type)
            .putExtra("launchMode", "stream")
            .putExtra("videoId", videoId)
            .putExtra("name", item.title)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        item.backdrop?.let { intent.putExtra("backdrop", it) }
        item.logo?.let { intent.putExtra("logo", it) }
        item.season?.let { intent.putExtra("season", it) }
        item.episode?.let { intent.putExtra("episode", it) }
        return start(intent, NUVIO)
    }

    private fun start(intent: Intent, pkg: String): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (pkg != SETTINGS) addRecent(pkg)
        true
    } catch (e: Exception) {
        false
    }

    /** "App sport" grid in the Sport tab: editable from any app's menu. */
    var sportApps: List<String>
        get() = prefs.getString("sport_apps", null)?.split(",")?.filter { it.isNotBlank() } ?: DEFAULT_SPORT_APPS
        set(value) {
            // the original sport apps keep their original order; apps you add go after them
            val v = value.distinct()
            val ordered = DEFAULT_SPORT_APPS.filter { it in v } + v.filter { it !in DEFAULT_SPORT_APPS }
            prefs.edit().putString("sport_apps", ordered.joinToString(",")).apply()
        }

    /** The dock's apps, in order (max [DOCK_MAX]). The Sport tab has its own dock. */
    fun dock(sport: Boolean = false): List<String> {
        // one-time switch from the old, longer Preferiti list to the 5-app dock
        if (!prefs.getBoolean("dock_v1", false)) {
            prefs.edit().putString("favourites", DEFAULT_FAVOURITES.joinToString(",")).putBoolean("dock_v1", true).apply()
        }
        val key = if (sport) "dock_sport" else "favourites"
        val default = if (sport) DEFAULT_SPORT_DOCK else DEFAULT_FAVOURITES
        return prefs.getString(key, null)?.split(",")?.filter { it.isNotBlank() } ?: default
    }

    fun setDock(value: List<String>, sport: Boolean = false) =
        prefs.edit().putString(if (sport) "dock_sport" else "favourites", value.take(dockMax).joinToString(",")).apply()

    // ---------------------------------------------------------------- dock options

    /** how many apps the dock holds (4–7) */
    var dockMax: Int
        get() = prefs.getInt("dockMax", DOCK_MAX)
        set(v) = prefs.edit().putInt("dockMax", v.coerceIn(4, 7)).apply()
    /** dock tile size: 0.85 small, 1 normal, 1.15 large */
    var dockScale: Float
        get() = prefs.getFloat("dockScale", 1f)
        set(v) = prefs.edit().putFloat("dockScale", v).apply()
    /** app names written on the dock tiles */
    var dockLabels: Boolean
        get() = prefs.getBoolean("dockLabels", false)
        set(v) = prefs.edit().putBoolean("dockLabels", v).apply()

    // ---------------------------------------------------------------- grid order and sizes

    /** "az", "used" (most opened first) or "custom" (your own order) */
    var gridOrder: String
        get() = prefs.getString("gridOrder", "az")!!
        set(v) = prefs.edit().putString("gridOrder", v).apply()
    /** the custom order (packages and folders), as last arranged */
    var gridCustom: List<String>
        get() = prefs.getString("gridCustom", "")!!.split(',').filter { it.isNotBlank() }
        set(v) = prefs.edit().putString("gridCustom", v.joinToString(",")).apply()
    fun uses(pkg: String) = prefs.getInt("uses_$pkg", 0)

    /** "big", "small" or "auto" (the grid alternates) */
    fun tileSize(pkg: String): String = when (pkg) {
        in prefs.getStringSet("bigApps", emptySet())!! -> "big"
        in prefs.getStringSet("smallApps", emptySet())!! -> "small"
        else -> "auto"
    }
    fun setTileSize(pkg: String, size: String) {
        val big = prefs.getStringSet("bigApps", emptySet())!! - pkg
        val small = prefs.getStringSet("smallApps", emptySet())!! - pkg
        prefs.edit().putStringSet("bigApps", if (size == "big") big + pkg else big)
            .putStringSet("smallApps", if (size == "small") small + pkg else small).apply()
    }

    // ---------------------------------------------------------------- custom images

    private fun imageFile(pkg: String) = java.io.File(java.io.File(context.filesDir, "appimg").apply { mkdirs() }, "$pkg.png")
    fun hasCustomImage(pkg: String) = imageFile(pkg).exists()
    fun setCustomImage(pkg: String, bmp: android.graphics.Bitmap) {
        imageFile(pkg).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        imagesChanged()
    }
    fun clearCustomImage(pkg: String) { imageFile(pkg).delete(); imagesChanged() }
    private fun imagesChanged() { prefs.edit().putLong("imagesVersion", System.currentTimeMillis()).apply(); invalidate() }
    val imagesVersion get() = prefs.getLong("imagesVersion", 0)

    /** The main dock (Esplora, Film, Serie TV). */
    var favourites: List<String>
        get() = dock(false)
        set(value) = setDock(value, false)

    fun dockFull(sport: Boolean = false) = dock(sport).size >= dockMax

    /** Puts [pkg] in the dock, in place of [replacing] when the dock is full. */
    fun addToDock(pkg: String, replacing: String? = null, sport: Boolean = false) {
        val d = dock(sport).toMutableList()
        if (pkg in d) return
        val i = replacing?.let { d.indexOf(it) } ?: -1
        if (i >= 0) d[i] = pkg else d += pkg
        setDock(d, sport)
    }

    fun removeFromDock(pkg: String, sport: Boolean = false) = setDock(dock(sport) - pkg, sport)

    fun moveInDock(pkg: String, delta: Int, sport: Boolean = false) {
        val d = dock(sport).toMutableList()
        val i = d.indexOf(pkg)
        val k = i + delta
        if (i < 0 || k !in d.indices) return
        d.removeAt(i)
        d.add(k, pkg)
        setDock(d, sport)
    }

    /** @return true if the app is now a favourite */
    fun toggleFavourite(pkg: String): Boolean {
        val favs = favourites
        val added = pkg !in favs
        favourites = if (added) favs + pkg else favs - pkg
        return added
    }

    var hidden: Set<String>
        get() = prefs.getStringSet("hidden", emptySet())!!
        set(value) = prefs.edit().putStringSet("hidden", value).apply()

    fun hide(pkg: String) {
        hidden = hidden + pkg
        favourites = favourites - pkg
    }

    val recents: List<String>
        get() = prefs.getString("recents", "")!!.split(",").filter { it.isNotBlank() }

    private fun addRecent(pkg: String) {
        val list = (listOf(pkg) + recents.filter { it != pkg }).take(12)
        prefs.edit().putString("recents", list.joinToString(",")).apply()
    }
}
