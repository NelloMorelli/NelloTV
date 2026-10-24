package com.nello.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * "Nero luminoso": a black background lit by whatever has the focus. Behind the selected app or
 * card the room takes on its colour (a wide soft light, a stronger one close by) and a thin plate
 * of lit glass sits just around it. It follows the focus as it moves and while the rows scroll.
 */
class GlowView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density

    var lit = false
        set(v) { field = v; visibility = if (v) VISIBLE else GONE; if (v) poke() }

    var night = false

    private var target: View? = null
    private var targetColor = Theme.accent

    // what's drawn now, gliding towards the target
    private val box = RectF()
    private var color = Theme.accent
    private var shown = false
    private var until = 0L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * dp }
    private val loc = IntArray(2)
    private val mine = IntArray(2)

    init {
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
    }

    /** The focus moved to [v]. */
    fun track(v: View?) {
        if (!lit) return
        target = v
        if (v == null) return
        val img = largestImage(v)
        val bmp = img?.let { bitmapOf(it) }
        targetColor = if (bmp == null) Theme.accent else synchronized(colors) { colors[bmp] } ?: run {
            pool.execute {
                val c = runCatching {
                    val soft = if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
                    ThemedTile.dominant(Bitmap.createScaledBitmap(soft, 48, 48, true))
                }.getOrDefault(Theme.accent)
                synchronized(colors) { colors[bmp] = c }
                post { if (target === v) { targetColor = c; poke() } }
            }
            targetColor
        }
        poke()
    }

    /** Something moved (a scroll, an animation): follow for a little while. */
    fun poke() {
        if (!lit) return
        until = SystemClock.uptimeMillis() + 700
        postInvalidateOnAnimation()
    }

    private fun largestImage(v: View): ImageView? {
        var best: ImageView? = null
        var area = 0
        fun walk(x: View) {
            if (x.visibility != VISIBLE) return
            if (x is ImageView && x.drawable != null && x.width * x.height > area) { best = x; area = x.width * x.height }
            if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(v)
        // only pictures that make up the element (not a small badge or logo)
        return best?.takeIf { area > v.width * v.height / 4 }
    }

    private fun bitmapOf(iv: ImageView): Bitmap? {
        var d = iv.drawable
        if (d is android.graphics.drawable.TransitionDrawable && d.numberOfLayers > 0) d = d.getDrawable(d.numberOfLayers - 1)
        return (d as? BitmapDrawable)?.bitmap
    }

    /** The element's box on screen, with its own scale and lift. */
    private fun targetBox(out: RectF): Boolean {
        val t = target ?: return false
        if (!t.isAttachedToWindow || !t.isShown) return false
        t.getLocationInWindow(loc)
        getLocationInWindow(mine)
        val x = (loc[0] - mine[0]).toFloat()
        val y = (loc[1] - mine[1]).toFloat()
        out.set(x, y, x + t.width * t.scaleX, y + t.height * t.scaleY)
        return out.width() > 0
    }

    private val goal = RectF()

    override fun onDraw(canvas: Canvas) {
        if (!lit) return
        val has = targetBox(goal)
        if (has) {
            if (!shown) { box.set(goal); shown = true }
            val k = 0.22f
            box.left += (goal.left - box.left) * k
            box.top += (goal.top - box.top) * k
            box.right += (goal.right - box.right) * k
            box.bottom += (goal.bottom - box.bottom) * k
            color = blend(color, targetColor, 0.14f).let { if (close(it, targetColor)) targetColor else it }
        }
        if (shown) paint(canvas) else canvas.drawColor(Color.BLACK)
        // keep going until the light has caught up with the element (it may move late: a scroll, a lift)
        val settled = !has || (Math.abs(goal.left - box.left) < 0.5f && Math.abs(goal.top - box.top) < 0.5f &&
            Math.abs(goal.right - box.right) < 0.5f && Math.abs(goal.bottom - box.bottom) < 0.5f && color == targetColor)
        if (SystemClock.uptimeMillis() < until || !settled) postInvalidateOnAnimation()
    }

    // the soft light is painted small (it's all blur anyway) and stretched by the GPU: one cheap
    // texture instead of full-screen gradients every frame
    private val small: Bitmap by lazy { Bitmap.createBitmap(SCALE_W, (SCALE_W * 9 / 16), Bitmap.Config.ARGB_8888) }
    private val smallCanvas by lazy { Canvas(small) }
    private val stretch = Paint(Paint.FILTER_BITMAP_FLAG)
    private val full = RectF()

    private fun paint(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val power = if (night) 0.5f else 1f
        val k = small.width / w
        val cx = box.centerX() * k
        val cy = box.centerY() * k
        val sw = small.width.toFloat()
        val sh = small.height.toFloat()
        smallCanvas.drawColor(Color.BLACK)
        // the room: a wide, soft light in the element's colour
        paint.shader = RadialGradient(cx, cy, sw * 0.62f,
            intArrayOf(alpha(color, 0.34f * power), alpha(color, 0.12f * power), 0), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        smallCanvas.drawRect(0f, 0f, sw, sh, paint)
        // close by: a stronger light hugging the element
        val r = (maxOf(box.width(), box.height()) * 0.95f * k).coerceAtLeast(1f)
        paint.shader = RadialGradient(cx, cy, r, intArrayOf(alpha(color, 0.55f * power), 0), null, Shader.TileMode.CLAMP)
        smallCanvas.drawRect(cx - r, cy - r, cx + r, cy + r, paint)
        paint.shader = null
        full.set(0f, 0f, w, h)
        canvas.drawBitmap(small, null, full, stretch)
        // a plate of lit glass just around it, brighter at the top edge (sharp, so full size)
        val o = 9 * dp
        val plate = RectF(box.left - o, box.top - o, box.right + o, box.bottom + o)
        val radius = 18 * dp * Theme.roundness
        paint.shader = LinearGradient(0f, plate.top, 0f, plate.bottom, alpha(lighten(color), 0.20f * power), alpha(color, 0.07f * power), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(plate, radius, radius, paint)
        paint.shader = null
        rim.shader = LinearGradient(0f, plate.top, 0f, plate.bottom, alpha(Color.WHITE, 0.30f * power), alpha(lighten(color), 0.06f * power), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(plate, radius, radius, rim)
    }

    private fun alpha(c: Int, a: Float) = (c and 0xFFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    private fun lighten(c: Int): Int {
        val hsv = FloatArray(3); Color.colorToHSV(c, hsv)
        hsv[1] *= 0.6f; hsv[2] = 1f
        return Color.HSVToColor(hsv)
    }

    private fun close(a: Int, b: Int) =
        Math.abs(Color.red(a) - Color.red(b)) < 6 && Math.abs(Color.green(a) - Color.green(b)) < 6 && Math.abs(Color.blue(a) - Color.blue(b)) < 6

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) + ((((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t)).toInt().coerceIn(0, 255)
        return Color.rgb(ch(16), ch(8), ch(0))
    }

    companion object {
        private const val SCALE_W = 240
        private val pool = Executors.newSingleThreadExecutor()
        private val colors = WeakHashMap<Bitmap, Int>()
    }
}
