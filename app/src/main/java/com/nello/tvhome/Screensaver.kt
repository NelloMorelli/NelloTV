package com.nello.tvhome

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Always-on screensaver: the wallpaper (dimmed) with a large clock and the date. The clock drifts
 * a little every minute so nothing burns in. Used as an overlay on the launcher (whose UI melts
 * away into it) and as [ScreensaverActivity] over other apps.
 */
class ScreensaverView(context: Context, withWallpaper: Boolean) : FrameLayout(context) {

    companion object {
        const val BACKGROUND = "background_now.jpg"
        /** after this long, let the Fire TV go to standby */
        const val MAX_MS = 30 * 60_000L
    }

    private val dp = resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val ease = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    private val dim = View(context).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
    private val date = TextView(context).apply {
        textSize = 20f
        typeface = Fonts.light
        setTextColor(0xB3FFFFFF.toInt())
        letterSpacing = 0.08f
        setShadowLayer(12f, 0f, 2f, 0x80000000.toInt())
    }
    private val clockBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        alpha = 0f
        addView(GlassClock(context), LinearLayout.LayoutParams(-1, -2))
        addView(date, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (-14 * resources.displayMetrics.density).toInt() })
    }

    init {
        isFocusable = false
        if (withWallpaper) {
            setBackgroundColor(Color.BLACK)
            addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                val f = File(context.filesDir, BACKGROUND)
                if (f.exists()) setImageBitmap(BitmapFactory.decodeFile(f.path)) else setImageResource(R.drawable.wallpaper)
            }, LayoutParams(-1, -1))
        }
        addView(dim, LayoutParams(-1, -1))
        addView(clockBox, LayoutParams(-1, -2, Gravity.CENTER))
    }

    fun start(onTimeout: () -> Unit) {
        date.text = today()
        dim.animate().alpha(0.62f).setDuration(5000).setInterpolator(ease).start()
        clockBox.translationY = 30 * dp
        clockBox.animate().alpha(1f).translationY(0f).setStartDelay(700).setDuration(2400).setInterpolator(ease).start()
        main.postDelayed(::drift, 60_000)
        main.postDelayed(onTimeout, MAX_MS)
    }

    /** Hide the clock and lift the dimming (before the UI comes back). */
    fun end(duration: Long, done: () -> Unit) {
        main.removeCallbacksAndMessages(null)
        clockBox.animate().cancel()
        clockBox.animate().alpha(0f).setStartDelay(0).setDuration(duration / 2).start()
        dim.animate().cancel()
        dim.animate().alpha(0f).setDuration(duration).withEndAction(done).start()
    }

    private fun drift() {
        val dx = ((Math.random() - 0.5) * 2 * 260 * dp).toFloat()
        val dy = ((Math.random() - 0.5) * 2 * 140 * dp).toFloat()
        clockBox.animate().alpha(0f).setStartDelay(0).setDuration(1600).withEndAction {
            clockBox.translationX = dx
            clockBox.translationY = dy
            date.text = today()
            clockBox.animate().alpha(1f).setDuration(1600).start()
        }.start()
        main.postDelayed(::drift, 60_000)
    }

    private fun today() = SimpleDateFormat("EEEE d MMMM", Locale.ITALY).format(Date()).replaceFirstChar { it.uppercase() }

    override fun onDetachedFromWindow() {
        main.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}

/** The screensaver over other apps: shown in place of Amazon's (see WakeService). */
class ScreensaverActivity : Activity() {

    private lateinit var view: ScreensaverView
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = ScreensaverView(this, withWallpaper = true)
        view.alpha = 0f
        setContentView(view)
        view.animate().alpha(1f).setDuration(1200).start()
        view.start {
            // after the time limit: release the screen and go to standby
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WakeService.instance?.sleep()
            finish()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && !closing) {
            closing = true
            view.animate().alpha(0f).setDuration(500).withEndAction {
                finish()
                overridePendingTransition(0, 0)
                // Amazon's screensaver is still underneath: close it too
                WakeService.instance?.dismissAmbient()
            }.start()
        }
        return true
    }
}

/**
 * The always-on clock, in liquid glass: the digits are made of glass — inside them the wallpaper,
 * blurred and brightened as if seen through it, a frosted top-to-bottom sheen, a thin light edge,
 * and a soft reflection that slides across now and then. Nothing else: hours and minutes.
 */
class GlassClock(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private var text = ""
    private val fmt = java.text.SimpleDateFormat("HH:mm", Locale.ITALY)
    private val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.medium
        textSize = 168 * resources.displayMetrics.scaledDensity
        letterSpacing = -0.02f
    }
    private val glass = android.graphics.Paint(textPaint)
    private val frost = android.graphics.Paint(textPaint)
    private val edge = android.graphics.Paint(textPaint).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 1.4f * dp
    }
    private val shine = android.graphics.Paint(textPaint)
    private val shadow = android.graphics.Paint(textPaint).apply {
        color = 0x59000000
        maskFilter = android.graphics.BlurMaskFilter(18 * dp, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val bounds = android.graphics.Rect()
    private val loc = IntArray(2)
    private val matrix = android.graphics.Matrix()
    private var source: Bitmap? = null
    private var sweep = -1f
    private var sweepAnim: android.animation.ValueAnimator? = null
    private val main = Handler(Looper.getMainLooper())

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // the blurred shadow needs it
        // what's behind the digits: the launcher's blurred wallpaper, brighter
        val blur = GlassView.blurred ?: run {
            val f = File(context.filesDir, ScreensaverView.BACKGROUND)
            val b = if (f.exists()) BitmapFactory.decodeFile(f.path) else BitmapFactory.decodeResource(resources, R.drawable.wallpaper)
            b?.let { GlassView.makeBlur(it) }
        }
        source = blur
        blur?.let {
            glass.shader = android.graphics.BitmapShader(it, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
            glass.colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(floatArrayOf(
                1.15f, 0f, 0f, 0f, 70f,
                0f, 1.15f, 0f, 0f, 70f,
                0f, 0f, 1.15f, 0f, 70f,
                0f, 0f, 0f, 1f, 0f,
            )))
        }
        tick()
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            // next minute boundary
            main.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 50)
        }
    }

    private fun tick() {
        text = fmt.format(Date())
        textPaint.getTextBounds(text, 0, text.length, bounds)
        requestLayout()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        main.postDelayed(ticker, 60_000 - System.currentTimeMillis() % 60_000 + 50)
        main.postDelayed(::startSweep, 2500)
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacksAndMessages(null)
        sweepAnim?.cancel()
        super.onDetachedFromWindow()
    }

    /** A soft reflection slides across the glass, then rests for a while. */
    private fun startSweep() {
        sweepAnim = android.animation.ValueAnimator.ofFloat(-0.4f, 1.4f).apply {
            duration = 4200
            interpolator = android.view.animation.PathInterpolator(0.45f, 0f, 0.55f, 1f)
            addUpdateListener { sweep = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    sweep = -1f
                    invalidate()
                    if (isAttachedToWindow) main.postDelayed(::startSweep, 14_000)
                }
            })
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fm = textPaint.fontMetrics
        // as wide as it's allowed (the text is centred when drawn), so it can never be cut
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) resources.displayMetrics.widthPixels
            else MeasureSpec.getSize(widthMeasureSpec)
        val h = (fm.descent - fm.ascent) * 0.82f + 48 * dp
        setMeasuredDimension(w, h.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val fm = textPaint.fontMetrics
        val x = (width - textPaint.measureText(text)) / 2f
        // optical centring: Poppins digits sit high in their line box
        val y = height / 2f - (fm.ascent + fm.descent) / 2f - bounds.height() * 0.06f
        val top = y + bounds.top
        val bottom = y + bounds.bottom
        canvas.drawText(text, x, y + 6 * dp, shadow)
        source?.let { b ->
            getLocationOnScreen(loc)
            val dm = resources.displayMetrics
            matrix.setScale(dm.widthPixels.toFloat() / b.width, dm.heightPixels.toFloat() / b.height)
            matrix.postTranslate(-loc[0].toFloat(), -loc[1].toFloat())
            glass.shader.setLocalMatrix(matrix)
            canvas.drawText(text, x, y, glass)
        }
        frost.shader = android.graphics.LinearGradient(0f, top, 0f, bottom,
            intArrayOf(0x66FFFFFF, 0x1FFFFFFF, 0x0DFFFFFF), floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawText(text, x, y, frost)
        edge.shader = android.graphics.LinearGradient(0f, top, 0f, bottom,
            intArrayOf(0xCCFFFFFF.toInt(), 0x40FFFFFF, 0x26FFFFFF), floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawText(text, x, y, edge)
        if (sweep > -0.5f) {
            val cx = width * sweep
            val band = 120 * dp
            shine.shader = android.graphics.LinearGradient(cx - band, top, cx + band, bottom,
                intArrayOf(0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
            canvas.drawText(text, x, y, shine)
        }
    }
}
