package com.nello.tvhome

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.PathInterpolator

/**
 * Overlay for the jump between the tabs and the top-right menu: the selector, shrunk to a dot of
 * light, shoots across in a slight arc leaving a fading trail, then blooms at the destination.
 */
class CometView(context: Context) : View(context) {

    private val dp = resources.displayMetrics.density
    private val trail = ArrayDeque<PointF>()
    private val pos = PointF()
    private var active = false
    private var glow = 1f
    private var anim: ValueAnimator? = null
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    init {
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun fly(from: PointF, to: PointF, onArrive: () -> Unit) {
        anim?.cancel()
        trail.clear()
        active = true
        // arc: control point above the straight line, higher for longer jumps
        val ctrl = PointF((from.x + to.x) / 2, minOf(from.y, to.y) - (Math.abs(to.x - from.x) * 0.06f).coerceAtMost(40 * dp))
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 820 // ≈410 ms at the Fire TV's 0.5x animation scale
            interpolator = PathInterpolator(0.45f, 0f, 0.25f, 1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                val u = 1 - t
                // quadratic Bézier from → ctrl → to
                pos.x = u * u * from.x + 2 * u * t * ctrl.x + t * t * to.x
                pos.y = u * u * from.y + 2 * u * t * ctrl.y + t * t * to.y
                trail.addLast(PointF(pos.x, pos.y))
                while (trail.size > 14) trail.removeFirst()
                // brightest in mid-flight
                glow = 0.7f + 0.3f * Math.sin(Math.PI * t).toFloat()
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    onArrive()
                    fadeTrail()
                }
            })
            start()
        }
    }

    /** After landing the trail keeps fading for a moment. */
    private fun fadeTrail() {
        anim = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 260
            addUpdateListener {
                if (trail.isNotEmpty()) trail.removeFirst()
                glow = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    active = false
                    trail.clear()
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!active) return
        // trail: segments getting thinner and fainter towards the tail
        val n = trail.size
        for (i in 1 until n) {
            val f = i.toFloat() / n
            line.strokeWidth = (1.5f + 5f * f) * dp
            line.alpha = (150 * f * glow).toInt()
            canvas.drawLine(trail.elementAt(i - 1).x, trail.elementAt(i - 1).y, trail.elementAt(i).x, trail.elementAt(i).y, line)
        }
        if (n == 0) return
        // halo + dot
        val r = 22 * dp * glow
        halo.shader = RadialGradient(pos.x, pos.y, r.coerceAtLeast(1f),
            intArrayOf(Color.argb((170 * glow).toInt(), 255, 255, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(pos.x, pos.y, r, halo)
        dot.alpha = (255 * glow).toInt()
        canvas.drawCircle(pos.x, pos.y, 5 * dp, dot)
    }
}
