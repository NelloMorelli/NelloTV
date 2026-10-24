package com.nello.tvhome

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Sfondo → Immagine dal dispositivo": every picture on the Fire TV (Download, Pictures, LocalSend…)
 * with a thumbnail; the chosen one is copied into the launcher, resized to the screen.
 */
class WallpaperPickerActivity : GlassPage() {

    private class Pic(val uri: Uri, val name: String, val folder: String)

    /** set when choosing an app's tile image instead of the wallpaper */
    private var forApp: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forApp = intent.getStringExtra(EXTRA_APP)
        if (forApp != null) {
            title("Immagine per ${intent.getStringExtra(EXTRA_APP_NAME)}", "Online da Google Play, oppure una tua immagine sulla Fire TV (ritagliata in 16:9)")
            online(forApp!!, intent.getStringExtra(EXTRA_APP_NAME) ?: "")
            header("Sulla Fire TV")
        } else title("Scegli uno sfondo", "Immagini sulla Fire TV · inviale con LocalSend o scaricale con Downloader")
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        } else load()
    }

    @Deprecated("ComponentActivity still delivers it")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) load()
        else info("Permesso negato", "serve l'accesso alle immagini")
    }

    private fun load() {
        lifecycleScope.launch {
            val pics = withContext(Dispatchers.IO) { query() }
            if (pics.isEmpty()) {
                info("Nessuna immagine sulla Fire TV", "")
                list.addView(TextView(this@WallpaperPickerActivity).apply {
                    text = "Invia una foto alla Fire TV con l'app LocalSend (già installata) oppure scaricala con Downloader, poi torna qui."
                    textSize = 14f
                    setTextColor(getColor(R.color.text_dim))
                    setPadding(dp(16), dp(8), dp(16), 0)
                })
                return@launch
            }
            var first: android.view.View? = null
            // whole folders, for "Le tue immagini a rotazione"
            val folders = if (forApp != null) emptyMap() else pics.groupBy { it.folder }.filter { it.value.size >= 2 }
            if (folders.isNotEmpty()) {
                header("A rotazione")
                folders.forEach { (folder, list) ->
                    val row = item("Usa la cartella ${folder.substringAfterLast('/').ifEmpty { "principale" }}", "${list.size} immagini") {
                        LauncherPrefs(this@WallpaperPickerActivity).apply { rotationFolder = folder; wallpaper = "rotation" }
                        Toast.makeText(this@WallpaperPickerActivity, "Sfondo a rotazione: ${list.size} immagini", Toast.LENGTH_SHORT).show()
                        finish()
                    } as LinearLayout
                    row.addView(ImageView(this@WallpaperPickerActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        load(list.first().uri) { size(320, 180) }
                    }, 0, LinearLayout.LayoutParams(dp(128), dp(72)).apply { marginEnd = dp(16) })
                    row.gravity = Gravity.CENTER_VERTICAL
                    if (first == null) first = row
                }
                header("Una sola immagine")
            }
            pics.forEach { pic ->
                val row = item(pic.name, pic.folder) { choose(pic) } as LinearLayout
                row.addView(ImageView(this@WallpaperPickerActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    load(pic.uri) { size(320, 180) }
                }, 0, LinearLayout.LayoutParams(dp(128), dp(72)).apply { marginEnd = dp(16) })
                row.gravity = Gravity.CENTER_VERTICAL
                if (first == null) first = row
            }
            if (forApp == null || currentFocus == null) first?.post { first?.requestFocus() }
        }
    }

    /** Alternatives found online, at the top; they take the focus as soon as they arrive. */
    private fun online(pkg: String, label: String) {
        header("Online")
        val status = info("Cerco su Google Play…", "")
        val statusRow = status.parent as android.view.View
        lifecycleScope.launch {
            val found = runCatching { AppArt.find(pkg, label) }.getOrElse { emptyList() }
            if (found.isEmpty()) {
                (statusRow as LinearLayout).let { (it.getChildAt(0) as TextView).text = "Nessuna immagine trovata online" }
                return@launch
            }
            var at = list.indexOfChild(statusRow)
            list.removeView(statusRow)
            var first: android.view.View? = null
            found.forEach { cand ->
                val row = item(cand.label, null) {
                    Apps(this@WallpaperPickerActivity).setCustomImage(pkg, cand.image)
                    Toast.makeText(this@WallpaperPickerActivity, "Immagine impostata", Toast.LENGTH_SHORT).show()
                    finish()
                } as LinearLayout
                row.addView(ImageView(this@WallpaperPickerActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(cand.image)
                }, 0, LinearLayout.LayoutParams(dp(128), dp(72)).apply { marginEnd = dp(16) })
                list.removeView(row)
                list.addView(row, at++, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(1) })
                if (first == null) first = row
            }
            first?.requestFocus()
        }
    }

    private fun query(): List<Pic> {
        val out = mutableListOf<Pic>()
        val cols = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH)
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, null, null,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < 200) {
                val id = c.getLong(0)
                out += Pic(
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                    c.getString(1) ?: "immagine",
                    (c.getString(2) ?: "").trimEnd('/'),
                )
            }
        }
        return out
    }

    private fun choose(pic: Pic) {
        forApp?.let { pkg ->
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val src = decodeScaled(this@WallpaperPickerActivity, pic.uri) ?: error("decode")
                        // centre crop to 16:9, 640×360
                        val h = minOf(src.height, src.width * 9 / 16)
                        val w = h * 16 / 9
                        val crop = Bitmap.createBitmap(src, (src.width - w) / 2, (src.height - h) / 2, w, h)
                        Apps(this@WallpaperPickerActivity).setCustomImage(pkg, Bitmap.createScaledBitmap(crop, 640, 360, true))
                    }.isSuccess
                }
                Toast.makeText(this@WallpaperPickerActivity, if (ok) "Immagine impostata" else "Impossibile leggere l'immagine", Toast.LENGTH_SHORT).show()
                if (ok) finish()
            }
            return
        }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { copyScaled(pic.uri) }.isSuccess }
            if (!ok) {
                Toast.makeText(this@WallpaperPickerActivity, "Impossibile leggere l'immagine", Toast.LENGTH_SHORT).show()
                return@launch
            }
            LauncherPrefs(this@WallpaperPickerActivity).apply {
                wallpaper = "custom"
                customVersion = System.currentTimeMillis()
            }
            Toast.makeText(this@WallpaperPickerActivity, "Sfondo impostato", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** Decode at about screen size and save as the launcher's own wallpaper file. */
    private fun copyScaled(uri: Uri) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        val dm = resources.displayMetrics
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= dm.widthPixels && bounds.outHeight / (sample * 2) >= dm.heightPixels) sample *= 2
        val bmp = contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("decode")
        File(filesDir, CUSTOM_FILE).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    }

    companion object {
        const val CUSTOM_FILE = "wallpaper_custom.jpg"
        const val EXTRA_APP = "app"
        const val EXTRA_APP_NAME = "app_name"

        /** The pictures of a folder (MediaStore relative path), by name. */
        fun folderImages(c: android.content.Context, folder: String): List<Uri> {
            val out = mutableListOf<Uri>()
            c.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.RELATIVE_PATH} = ?", arrayOf("$folder/"), "${MediaStore.Images.Media.DISPLAY_NAME} ASC",
            )?.use { cur -> while (cur.moveToNext()) out += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cur.getLong(0)) }
            return out
        }

        /** Decode about screen size. */
        fun decodeScaled(c: android.content.Context, uri: Uri): Bitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            c.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
            val dm = c.resources.displayMetrics
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= dm.widthPixels && bounds.outHeight / (sample * 2) >= dm.heightPixels) sample *= 2
            c.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }.getOrNull()
    }
}
