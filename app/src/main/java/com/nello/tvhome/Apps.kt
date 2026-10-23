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

    /** [all] plus the "App nascoste" tile when something is hidden. */
    fun grid(): List<AppInfo> {
        val count = hidden.count { it in installed() }
        return if (count == 0) all()
        else all() + AppInfo(HIDDEN_TILE, "App nascoste ($count)", null, context.getDrawable(android.R.drawable.ic_menu_view))
    }

    fun get(pkgs: List<String>): List<AppInfo> = installed().let { map -> pkgs.filter { it !in hidden }.mapNotNull { map[it] } }

    fun hiddenApps(): List<AppInfo> = installed().let { map -> hidden.mapNotNull { map[it] } }
        .sortedWith(compareBy(Collator.getInstance(Locale.ITALY)) { it.label })

    fun invalidate() {
        cache = null
    }

    private fun installed(): Map<String, AppInfo> = cache ?: load().also { cache = it }

    private fun load(): Map<String, AppInfo> {
        val result = linkedMapOf<String, AppInfo>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName || pkg in result) continue
                val ai = ri.activityInfo
                result[pkg] = AppInfo(
                    pkg = pkg,
                    label = ri.loadLabel(pm).toString(),
                    banner = ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm),
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
        prefs.edit().putString(if (sport) "dock_sport" else "favourites", value.take(DOCK_MAX).joinToString(",")).apply()

    /** The main dock (Esplora, Film, Serie TV). */
    var favourites: List<String>
        get() = dock(false)
        set(value) = setDock(value, false)

    fun dockFull(sport: Boolean = false) = dock(sport).size >= DOCK_MAX

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
