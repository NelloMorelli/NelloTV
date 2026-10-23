package com.nello.tvhome

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from the private GitHub repo's releases: tag "v<versionCode>" with the APK attached.
 * Android always asks to confirm the install (one "Installa" on the TV).
 */
object Updater {

    /** "0.39": the name of a version number (this build's, or a release's on GitHub). */
    fun name(code: Int = BuildConfig.VERSION_CODE) = "${BuildConfig.VERSION_MAJOR}.${code - BuildConfig.VERSION_START}"

    class Release(val version: Int, val notes: String, val apiAssetUrl: String)

    val configured get() = BuildConfig.UPDATE_REPO.isNotBlank() && BuildConfig.UPDATE_TOKEN.isNotBlank()

    private fun headers() = mapOf(
        "Authorization" to "Bearer ${BuildConfig.UPDATE_TOKEN}",
        "Accept" to "application/vnd.github+json",
    )

    /** The latest release if it's newer than this build, else null. */
    suspend fun check(): Release? {
        if (!configured) return null
        val o = JSONObject(Net.get("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest", headers()))
        val version = o.optString("tag_name").removePrefix("v").toIntOrNull() ?: return null
        if (version <= BuildConfig.VERSION_CODE) return null
        val assets = o.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") } ?: return null
        return Release(version, o.optString("body").trim(), apk.getString("url"))
    }

    /** Download the APK (private repo: through the API with the token) and open the installer. */
    suspend fun install(context: Context, r: Release) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "nellotv-${r.version}.apk")
        withContext(Dispatchers.IO) {
            var url = URL(r.apiAssetUrl)
            var conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            headers().forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.setRequestProperty("Accept", "application/octet-stream")
            // GitHub answers with a redirect to a signed download URL (no auth header there)
            if (conn.responseCode in 300..399) {
                url = URL(conn.getHeaderField("Location"))
                conn.disconnect()
                conn = url.openConnection() as HttpURLConnection
            }
            conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
