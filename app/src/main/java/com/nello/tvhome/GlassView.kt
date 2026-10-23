package com.nello.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Frosted glass panel: draws a pre-blurred copy of the wallpaper aligned to the screen,
 * then a light tint and a hairline border. Fire OS 8 is API 30, so RenderEffect blur isn't available.
 */
class GlassView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    companion object {
        /** night mode: dimmer glass everywhere */
        var night = false

        var blurred: Bitmap? = null

        /** Downscale + 3 box-blur passes: cheap and close enough to a gaussian at TV viewing distance. */
        fun makeBlur(src: Bitmap): Bitmap {
            val w = 240
            val h = w * src.height / src.width
            val small = Bitmap.createScaledBitmap(src, w, h, true)
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            repeat(3) { boxBlur(px, w, h, 6) }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }

        private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
            val tmp = IntArray(px.size)
            pass(px, tmp, w, h, r, horizontal = true)
            pass(tmp, px, w, h, r, horizontal = false)
        }

        private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            val lines = if (horizontal) h else w
            val len = if (horizontal) w else h
            for (line in 0 until lines) {
                fun idx(i: Int) = if (horizontal) line * w + i.coerceIn(0, len - 1) else i.coerceIn(0, len - 1) * w + line
                var rs = 0; var gs = 0; var bs = 0
                for (i in -r..r) {
                    val c = src[idx(i)]
                    rs += c shr 16 and 0xFF; gs += c shr 8 and 0xFF; bs += c and 0xFF
                }
                val n = 2 * r + 1
                for (i in 0 until len) {
                    dst[idx(i)] = (0xFF shl 24) or ((rs / n) shl 16) or ((gs / n) shl 8) or (bs / n)
                    val out = src[idx(i - r)]
                    val inn = src[idx(i + r + 1)]
                    rs += (inn shr 16 and 0xFF) - (out shr 16 and 0xFF)
                    gs += (inn shr 8 and 0xFF) - (out shr 8 and 0xFF)
                    bs += (inn and 0xFF) - (out and 0xFF)
                }
            }
        }
    }

    private val radius = 22f * resources.displayMetrics.density
    private val clip = Path()
    private val rect = RectF()
    private val loc = IntArray(2)
    private val dst = Rect()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x24FFFFFF }
    private val focusTint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x52FFFFFF }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
        color = 0x40FFFFFF
    }

    init {
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val r = minOf(radius, h / 2f)
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
    }

    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shaderMatrix = android.graphics.Matrix()
    private var shaderFor: Bitmap? = null

    override fun onDraw(canvas: Canvas) {
        val r = minOf(radius, height / 2f)
        // blurred wallpaper through a shader on a rounded rect: much cheaper on the GPU than clipPath
        blurred?.let { b ->
            if (shaderFor !== b) {
                glassPaint.shader = android.graphics.BitmapShader(b, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                shaderFor = b
            }
            getLocationOnScreen(loc)
            val dm = resources.displayMetrics
            shaderMatrix.setScale(dm.widthPixels.toFloat() / b.width, dm.heightPixels.toFloat() / b.height)
            shaderMatrix.postTranslate(-loc[0].toFloat(), -loc[1].toFloat())
            glassPaint.shader.setLocalMatrix(shaderMatrix)
            canvas.drawRoundRect(rect, r, r, glassPaint)
        }
        if (frosted && !(isFocused && solidFocus)) {
            val key = if (night) -height else height
            if (frostHeight != key) {
                frostHeight = key
                frostPaint.shader = android.graphics.LinearGradient(0f, 0f, 0f, height.toFloat(),
                    if (night) intArrayOf(0x1FFFFFFF, 0x0FFFFFFF, 0x08FFFFFF) else intArrayOf(0x47FFFFFF, 0x24FFFFFF, 0x14FFFFFF),
                    floatArrayOf(0f, 0.45f, 1f), android.graphics.Shader.TileMode.CLAMP)
            }
            canvas.drawRoundRect(rect, r, r, frostPaint)
        } else {
            canvas.drawRoundRect(rect, r, r, if (isFocused) (if (solidFocus) solidPaint else focusTint) else tint)
        }
        val d = resources.displayMetrics.density
        border.strokeWidth = if (isFocused) 2 * d else d
        border.color = if (isFocused) 0xFFFFFFFF.toInt() else 0x40FFFFFF
        val half = border.strokeWidth / 2
        canvas.drawRoundRect(half, half, width - half, height - half, r, r, border)
    }

    /** brighter frosted glass with a soft top-to-bottom gradient, like the pop-ups (dock, top-right menu) */
    var frosted = false
        set(v) { field = v; invalidate() }
    private val frostPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var frostHeight = -1

    /** focused: solid white disc instead of the brighter glass (round top-right buttons) */
    var solidFocus = false
    private val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() }

    /** false for a plain container (e.g. the pill around the top-right icons) */
    var zoomOnFocus = true

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!zoomOnFocus) return
        val s = if (gainFocus) 1.08f else 1f
        animate().scaleX(s).scaleY(s).setDuration(440).setInterpolator(android.view.animation.DecelerateInterpolator(1.4f)).start()
        invalidate()
    }
}
