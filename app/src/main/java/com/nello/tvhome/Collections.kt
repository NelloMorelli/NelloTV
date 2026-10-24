package com.nello.tvhome

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A named group of apps: a folder in the grid, or one of "Le tue righe" in Esplora. */
data class AppGroup(val id: String, val name: String, val apps: List<String>)

/**
 * Folders, custom rows and recently used apps, kept with the rest of the app settings (so the
 * settings backup carries them too).
 */
class Collections(context: Context) {
    private val prefs = context.getSharedPreferences("apps", Context.MODE_PRIVATE)

    companion object {
        /** a folder in the grid is an app tile with this package prefix */
        const val FOLDER = "folder:"
        const val RECENTS_MAX = 12
    }

    private fun read(key: String): List<AppGroup> = runCatching {
        val a = JSONArray(prefs.getString(key, "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val apps = o.getJSONArray("apps")
            AppGroup(o.getString("id"), o.getString("name"), (0 until apps.length()).map { apps.getString(it) })
        }
    }.getOrElse { emptyList() }

    private fun write(key: String, groups: List<AppGroup>) {
        val a = JSONArray()
        groups.forEach { g -> a.put(JSONObject().put("id", g.id).put("name", g.name).put("apps", JSONArray(g.apps))) }
        prefs.edit().putString(key, a.toString()).apply()
    }

    private fun newId() = System.currentTimeMillis().toString(36)

    // ---------------------------------------------------------------- folders

    var folders: List<AppGroup>
        get() = read("folders")
        set(v) = write("folders", v)

    fun folder(id: String) = folders.firstOrNull { it.id == id }

    fun folderOf(pkg: String) = folders.firstOrNull { pkg in it.apps }

    fun createFolder(name: String, firstApp: String): AppGroup {
        val g = AppGroup(newId(), name, listOf(firstApp))
        // an app lives in one folder at a time
        folders = folders.map { it.copy(apps = it.apps - firstApp) }.filter { it.apps.isNotEmpty() } + g
        return g
    }

    fun addToFolder(id: String, pkg: String) {
        folders = folders.map { f ->
            if (f.id == id) f.copy(apps = (f.apps - pkg) + pkg) else f.copy(apps = f.apps - pkg)
        }.filter { it.apps.isNotEmpty() || it.id == id }
    }

    /** Takes [pkg] out of its folder; an emptied folder disappears. */
    fun removeFromFolder(pkg: String) {
        folders = folders.map { it.copy(apps = it.apps - pkg) }.filter { it.apps.isNotEmpty() }
    }

    fun renameFolder(id: String, name: String) {
        folders = folders.map { if (it.id == id) it.copy(name = name) else it }
    }

    fun deleteFolder(id: String) {
        folders = folders.filter { it.id != id }
    }

    // ---------------------------------------------------------------- custom rows

    var rows: List<AppGroup>
        get() = read("custom_rows")
        set(v) = write("custom_rows", v)

    fun createRow(name: String): AppGroup = AppGroup(newId(), name, emptyList()).also { rows = rows + it }

    fun toggleInRow(id: String, pkg: String) {
        rows = rows.map { r -> if (r.id != id) r else if (pkg in r.apps) r.copy(apps = r.apps - pkg) else r.copy(apps = r.apps + pkg) }
    }

    fun moveInRow(id: String, pkg: String, delta: Int) {
        rows = rows.map { r ->
            if (r.id != id) return@map r
            val i = r.apps.indexOf(pkg)
            val j = (i + delta).coerceIn(0, r.apps.size - 1)
            if (i < 0 || i == j) r else r.copy(apps = r.apps.toMutableList().apply { add(j, removeAt(i)) })
        }
    }

    fun renameRow(id: String, name: String) {
        rows = rows.map { if (it.id == id) it.copy(name = name) else it }
    }

    fun moveRow(id: String, delta: Int) {
        val l = rows.toMutableList()
        val i = l.indexOfFirst { it.id == id }
        val j = (i + delta).coerceIn(0, l.size - 1)
        if (i < 0 || i == j) return
        l.add(j, l.removeAt(i))
        rows = l
    }

    fun deleteRow(id: String) {
        rows = rows.filter { it.id != id }
    }

    // ---------------------------------------------------------------- recents

    /** Most recent first. */
    var recents: List<String>
        get() = prefs.getString("recents", "")!!.split(',').filter { it.isNotBlank() }
        set(v) = prefs.edit().putString("recents", v.take(RECENTS_MAX).joinToString(",")).apply()

    fun used(pkg: String) {
        // launch count, for "Più usate"
        prefs.edit().putInt("uses_$pkg", prefs.getInt("uses_$pkg", 0) + 1).apply()
        val r = recents
        if (r.firstOrNull() == pkg) return
        recents = listOf(pkg) + (r - pkg)
    }
}
