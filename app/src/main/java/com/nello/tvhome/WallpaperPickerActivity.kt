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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title("Scegli uno sfondo", "Immagini sulla Fire TV · inviale con LocalSend o scaricale con Downloader")
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
                info("Nessuna immagine trovata", "")
                list.addView(TextView(this@WallpaperPickerActivity).apply {
                    text = "Invia una foto alla Fire TV con l'app LocalSend (già installata) oppure scaricala con Downloader, poi torna qui."
                    textSize = 14f
                    setTextColor(getColor(R.color.text_dim))
                    setPadding(dp(16), dp(8), dp(16), 0)
                })
                return@launch
            }
            var first: android.view.View? = null
            pics.forEach { pic ->
                val row = item(pic.name, pic.folder) { choose(pic) } as LinearLayout
                row.addView(ImageView(this@WallpaperPickerActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    load(pic.uri) { size(320, 180) }
                }, 0, LinearLayout.LayoutParams(dp(128), dp(72)).apply { marginEnd = dp(16) })
                row.gravity = Gravity.CENTER_VERTICAL
                if (first == null) first = row
            }
            first?.post { first?.requestFocus() }
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
    }
}
