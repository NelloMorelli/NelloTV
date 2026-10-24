package com.nello.tvhome

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Settings backup: every Nello TV preference (dock, rows, folders, theme, team, Sportio playlist,
 * Nuvio link…) and your wallpaper image, in one file in Download — send it to the other Fire TV
 * with LocalSend and restore it there. Caches and history are left out.
 */
object Backup {
    private val FILES = listOf("launcher", "apps", "app_names", "theme", "nuvio_sync", "sportio", "perte")
    private const val MAGIC = "nellotv-backup"

    private fun dump(c: Context, name: String): JSONObject {
        val o = JSONObject()
        c.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) ->
            val e = JSONObject()
            when (v) {
                is String -> e.put("t", "s").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is Boolean -> e.put("t", "b").put("v", v)
                is Set<*> -> e.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> return@forEach
            }
            o.put(k, e)
        }
        return o
    }

    private fun load(c: Context, name: String, o: JSONObject) {
        val ed = c.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        o.keys().forEach { k ->
            val e = o.getJSONObject(k)
            when (e.getString("t")) {
                "s" -> ed.putString(k, e.getString("v"))
                "i" -> ed.putInt(k, e.getInt("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "set" -> ed.putStringSet(k, e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
            }
        }
        ed.commit()
    }

    /** Writes the backup to Download and returns its name. */
    fun export(c: Context): String {
        val root = JSONObject().put("magic", MAGIC).put("version", BuildConfig.VERSION_NAME).put("created", System.currentTimeMillis())
        val prefs = JSONObject()
        FILES.forEach { prefs.put(it, dump(c, it)) }
        root.put("prefs", prefs)
        File(c.filesDir, WallpaperPickerActivity.CUSTOM_FILE).takeIf { it.exists() }?.let {
            root.put("wallpaper", Base64.encodeToString(it.readBytes(), Base64.NO_WRAP))
        }
        // app images you chose
        val imgs = JSONObject()
        File(c.filesDir, "appimg").listFiles()?.forEach { f -> imgs.put(f.name, Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)) }
        if (imgs.length() > 0) root.put("appImages", imgs)
        val name = "NelloTV-backup-" + java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".json"
        val bytes = root.toString().toByteArray()
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
        }
        val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no access to Download")
        c.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        return name
    }

    /** Backups found in Download (newest first): name and uri. */
    fun list(c: Context): List<Pair<String, android.net.Uri>> {
        val out = mutableListOf<Pair<String, android.net.Uri>>()
        c.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME),
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?", arrayOf("NelloTV-backup-%.json"),
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
        )?.use { cur ->
            while (cur.moveToNext()) out += cur.getString(1) to android.content.ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), cur.getLong(0))
        }
        return out
    }

    /** Restores a backup file; false if it isn't one. */
    fun restore(c: Context, uri: android.net.Uri): Boolean {
        val text = c.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return false
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return false
        if (root.optString("magic") != MAGIC) return false
        val prefs = root.getJSONObject("prefs")
        FILES.forEach { name -> prefs.optJSONObject(name)?.let { load(c, name, it) } }
        root.optString("wallpaper").takeIf { it.isNotEmpty() }?.let {
            File(c.filesDir, WallpaperPickerActivity.CUSTOM_FILE).writeBytes(Base64.decode(it, Base64.NO_WRAP))
        }
        root.optJSONObject("appImages")?.let { imgs ->
            val dir = File(c.filesDir, "appimg").apply { mkdirs() }
            imgs.keys().forEach { k -> File(dir, k).writeBytes(Base64.decode(imgs.getString(k), Base64.NO_WRAP)) }
        }
        return true
    }
}
