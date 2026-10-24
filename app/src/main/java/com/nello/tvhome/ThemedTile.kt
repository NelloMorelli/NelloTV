package com.nello.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.io.File

/**
 * A horizontal cover for apps that only have a square icon: a background in the icon's own
 * colour (light from the top left, deeper at the bottom right), the icon large and faint behind,
 * the icon crisp in the middle and the app's name underneath. Made once and kept on disk.
 */
object ThemedTile {
    private const val W = 640
    private const val H = 360

    fun get(c: Context, pkg: String, label: String, icon: Drawable?): Drawable? {
        if (icon == null) return null
        val dir = File(c.filesDir, "themed").apply { mkdirs() }
        val f = File(dir, "$pkg-${label.hashCode()}-v2.png")
        if (f.exists()) BitmapFactoryDecode(f)?.let { return BitmapDrawable(c.resources, it) }
        val bmp = runCatching { draw(c, label, icon) }.getOrNull() ?: return null
        runCatching { f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return BitmapDrawable(c.resources, bmp)
    }

    private fun BitmapFactoryDecode(f: File) = android.graphics.BitmapFactory.decodeFile(f.path)

    private fun iconBitmap(d: Drawable, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val dd = d.constantState?.newDrawable()?.mutate() ?: d
        dd.setBounds(0, 0, size, size)
        dd.draw(Canvas(b))
        return b
    }

    /** The icon's main colour: the average hue of its vivid pixels (grey icons get a slate blue). */
    private fun dominant(icon: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(icon, 24, 24, true)
        var x = 0.0; var y = 0.0; var weight = 0.0; var sat = 0.0; var n = 0
        val hsv = FloatArray(3)
        for (i in 0 until 24) for (j in 0 until 24) {
            val p = small.getPixel(i, j)
            if (Color.alpha(p) < 200) continue
            Color.colorToHSV(p, hsv)
            if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue
            val w = (hsv[1] * hsv[2]).toDouble()
            x += Math.cos(Math.toRadians(hsv[0].toDouble())) * w
            y += Math.sin(Math.toRadians(hsv[0].toDouble())) * w
            weight += w; sat += hsv[1]; n++
        }
        if (n < 8) return Color.HSVToColor(floatArrayOf(220f, 0.25f, 0.45f))
        val hue = ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360).toFloat()
        return Color.HSVToColor(floatArrayOf(hue, (sat / n).toFloat().coerceIn(0.45f, 0.85f), 0.62f))
    }

    private fun shade(color: Int, v: Float, s: Float = 1f): Int {
        val hsv = FloatArray(3); Color.colorToHSV(color, hsv)
        hsv[2] = v; hsv[1] = (hsv[1] * s).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    private fun draw(c: Context, label: String, icon: Drawable): Bitmap {
        val ic = iconBitmap(icon, 256)
        val base = dominant(ic)
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // background: the colour, lighter at the top left, deep at the bottom right
        p.shader = LinearGradient(0f, 0f, W.toFloat(), H.toFloat(), shade(base, 0.62f), shade(base, 0.22f, 1.1f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
        // a soft light behind the icon
        p.shader = RadialGradient(W / 2f, H * 0.42f, H * 0.7f, intArrayOf(0x40FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
        p.shader = null
        // the icon huge and faint, off to the right
        p.alpha = 34
        cv.drawBitmap(ic, null, android.graphics.RectF(W * 0.52f, -H * 0.15f, W * 0.52f + H * 1.3f, H * 1.15f), p)
        // the icon crisp in the middle, with a light shadow
        p.alpha = 255
        p.setShadowLayer(18f, 0f, 6f, 0x66000000)
        val s = 150f
        cv.drawBitmap(ic, null, android.graphics.RectF(W / 2f - s / 2, H * 0.40f - s / 2, W / 2f + s / 2, H * 0.40f + s / 2), p)
        p.clearShadowLayer()
        // the name
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 34f
            typeface = Fonts.medium
            textAlign = Paint.Align.CENTER
            setShadowLayer(8f, 0f, 2f, 0x80000000.toInt())
        }
        var name = label
        while (t.measureText(name) > W - 80 && name.length > 4) name = name.dropLast(2) + "…"
        cv.drawText(name, W / 2f, H * 0.82f, t)
        return out
    }
}
