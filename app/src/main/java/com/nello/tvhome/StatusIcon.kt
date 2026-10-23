package com.nello.tvhome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Small top-bar glyphs drawn in code: Wi-Fi with signal bars, Ethernet, offline, VPN shield, gear. */
class StatusIcon @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Kind { WIFI, ETHERNET, OFFLINE, VPN, GEAR, MIC, HEADPHONES }

    var kind = Kind.WIFI
        set(value) { field = value; invalidate() }

    /** Wi-Fi bars 0..3; for VPN 1 = active, 0 = off */
    var level = 3
        set(value) { field = value; invalidate() }

    private var on = 0xFFFFFFFF.toInt()
    private var off = 0x4DFFFFFF
    private val red = 0xFFE5484D.toInt()
    private val green = 0xFF46C37B.toInt()

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    /** 0 = white glyph, 1 = dark glyph (under the white sliding pill); set by FocusGroup */
    var ink = 0f
        set(value) { if (field != value) { field = value; invalidate() } }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val v = (255 - (255 - 0x11) * ink).toInt()
        on = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        off = (0x4D shl 24) or (v shl 16) or (v shl 8) or v
        stroke.strokeWidth = s * 0.09f
        when (kind) {
            Kind.WIFI, Kind.OFFLINE -> drawWifi(canvas, s, cx, cy)
            Kind.ETHERNET -> drawEthernet(canvas, s, cx, cy)
            Kind.VPN -> drawShield(canvas, s, cx, cy)
            Kind.GEAR -> drawGear(canvas, s, cx, cy)
            Kind.MIC -> drawMic(canvas, s, cx, cy)
            Kind.HEADPHONES -> drawHeadphones(canvas, s, cx, cy)
        }
    }

    private fun drawWifi(canvas: Canvas, s: Float, cx: Float, cy: Float) {
        val baseY = cy + s * 0.32f
        val offline = kind == Kind.OFFLINE
        for (i in 1..3) {
            val r = s * (0.16f + 0.17f * i)
            rect.set(cx - r, baseY - r, cx + r, baseY + r)
            stroke.color = if (offline) off else if (level >= i) on else off
            canvas.drawArc(rect, 225f, 90f, false, stroke)
        }
        fill.color = if (offline) off else on
        canvas.drawCircle(cx, baseY - s * 0.04f, s * 0.07f, fill)
        if (offline) {
            stroke.color = red
            canvas.drawLine(cx - s * 0.36f, cy - s * 0.36f, cx + s * 0.36f, cy + s * 0.36f, stroke)
        }
    }

    private fun drawEthernet(canvas: Canvas, s: Float, cx: Float, cy: Float) {
        stroke.color = on
        // three computers linked: a small network glyph
        val box = s * 0.22f
        rect.set(cx - box / 2, cy - s * 0.4f, cx + box / 2, cy - s * 0.4f + box)
        canvas.drawRect(rect, stroke)
        canvas.drawLine(cx, cy - s * 0.4f + box, cx, cy, stroke)
        canvas.drawLine(cx - s * 0.3f, cy, cx + s * 0.3f, cy, stroke)
        for (x in listOf(cx - s * 0.3f, cx + s * 0.3f)) {
            canvas.drawLine(x, cy, x, cy + s * 0.12f, stroke)
            rect.set(x - box / 2, cy + s * 0.12f, x + box / 2, cy + s * 0.12f + box)
            canvas.drawRect(rect, stroke)
        }
    }

    private fun drawShield(canvas: Canvas, s: Float, cx: Float, cy: Float) {
        val active = level > 0
        path.reset()
        path.moveTo(cx, cy - s * 0.42f)
        path.lineTo(cx + s * 0.34f, cy - s * 0.28f)
        path.quadTo(cx + s * 0.34f, cy + s * 0.22f, cx, cy + s * 0.44f)
        path.quadTo(cx - s * 0.34f, cy + s * 0.22f, cx - s * 0.34f, cy - s * 0.28f)
        path.close()
        if (active) {
            fill.color = green
            canvas.drawPath(path, fill)
            stroke.color = 0xFF0B2A18.toInt()
            canvas.drawLine(cx - s * 0.13f, cy, cx - s * 0.02f, cy + s * 0.12f, stroke)
            canvas.drawLine(cx - s * 0.02f, cy + s * 0.12f, cx + s * 0.16f, cy - s * 0.1f, stroke)
        } else {
            stroke.color = on
            stroke.strokeWidth = s * 0.08f
            canvas.drawPath(path, stroke)
        }
    }

    /** Cog: filled body with 8 rounded teeth and a hole in the middle. */
    private fun drawGear(canvas: Canvas, s: Float, cx: Float, cy: Float) {
        val teeth = 8
        val rOut = s * 0.46f
        val rIn = s * 0.34f
        path.reset()
        for (i in 0 until teeth * 4) {
            // each tooth: up, top, down, valley
            val a = Math.toRadians(i * 360.0 / (teeth * 4) - 90 + 360.0 / (teeth * 8))
            val r = if (i % 4 == 1 || i % 4 == 2) rOut else rIn
            val x = cx + (Math.cos(a) * r).toFloat()
            val y = cy + (Math.sin(a) * r).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        path.addCircle(cx, cy, s * 0.14f, Path.Direction.CCW)
        path.fillType = Path.FillType.EVEN_ODD
        fill.color = on
        canvas.drawPath(path, fill)
    }
    /** Classic microphone: capsule, holder arc, stem and base. */
    private fun drawMic(canvas: Canvas, s: Float, cx: Float, cy: Float) {
        fill.color = on
        stroke.color = on
        stroke.strokeWidth = s * 0.09f
        // capsule
        rect.set(cx - s * 0.14f, cy - s * 0.46f, cx + s * 0.14f, cy + s * 0.08f)
        canvas.drawRoundRect(rect, s * 0.14f, s * 0.14f, fill)
        // holder
        rect.set(cx - s * 0.28f, cy - s * 0.22f, cx + s * 0.28f, cy + s * 0.24f)
        canvas.drawArc(rect, 0f, 180f, false, stroke)
        // stem and base
        canvas.drawLine(cx, cy + s * 0.24f, cx, cy + s * 0.40f, stroke)
        canvas.drawLine(cx - s * 0.16f, cy + s * 0.42f, cx + s * 0.16f, cy + s * 0.42f, stroke)
    }
    /** Over-ear headphones; filled cups when connected (level 1), outlined when not. */
    private fun drawHeadphones(canvas: Canvas, s: Float, cx: Float, cy: Float) {
        val connected = level > 0
        stroke.color = on
        stroke.strokeWidth = s * 0.09f
        // headband
        rect.set(cx - s * 0.36f, cy - s * 0.40f, cx + s * 0.36f, cy + s * 0.32f)
        canvas.drawArc(rect, 180f, 180f, false, stroke)
        // ear cups
        fill.color = on
        for (x in listOf(cx - s * 0.36f, cx + s * 0.36f)) {
            rect.set(x - s * 0.11f, cy - s * 0.02f, x + s * 0.11f, cy + s * 0.40f)
            if (connected) canvas.drawRoundRect(rect, s * 0.08f, s * 0.08f, fill)
            else canvas.drawRoundRect(rect, s * 0.08f, s * 0.08f, stroke)
        }
    }
}
