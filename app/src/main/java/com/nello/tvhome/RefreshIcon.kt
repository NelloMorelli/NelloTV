package com.nello.tvhome

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * The circular arrow next to "Per te". It follows the label's text colour and spins while new
 * suggestions load, easing into its last turn when they arrive.
 */
class RefreshIcon(private val sizePx: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    private var angle = 0f
    private var spin: ValueAnimator? = null
    var color = 0xFFFFFFFF.toInt()
        set(v) { field = v; invalidateSelf() }

    fun start() {
        if (spin?.isRunning == true) return
        spin = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1400 // ≈700 ms per turn at 0.5x
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            val base = angle
            addUpdateListener { angle = (base + it.animatedValue as Float) % 360f; invalidateSelf() }
            start()
        }
    }

    /** Finish the current turn softly, then rest upright. */
    fun stop() {
        val s = spin ?: return
        s.cancel()
        spin = null
        ValueAnimator.ofFloat(angle, 360f).apply {
            duration = (((360f - angle) / 360f) * 1400 + 300).toLong()
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener { angle = (it.animatedValue as Float) % 360f; invalidateSelf() }
            start()
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = b.width().toFloat()
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        val r = s * 0.34f
        paint.strokeWidth = s * 0.11f
        paint.color = color
        head.color = color
        canvas.save()
        canvas.rotate(angle, cx, cy)
        rect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, -60f, 290f, false, paint)
        // arrowhead at the arc's start, pointing along it
        val a = Math.toRadians(-60.0)
        val hx = cx + (r * Math.cos(a)).toFloat(); val hy = cy + (r * Math.sin(a)).toFloat()
        path.reset()
        path.moveTo(hx + s * 0.16f, hy - s * 0.02f)
        path.lineTo(hx - s * 0.06f, hy - s * 0.17f)
        path.lineTo(hx - s * 0.02f, hy + s * 0.12f)
        path.close()
        canvas.drawPath(path, head)
        canvas.restore()
    }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; head.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; head.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
