package com.nello.tvhome

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
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
        textSize = 22f
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        setTextColor(0x99FFFFFF.toInt())
        letterSpacing = 0.05f
    }
    private val clockBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        alpha = 0f
        addView(TextClock(context).apply {
            format12Hour = "HH:mm"; format24Hour = "HH:mm"
            textSize = 120f
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            setTextColor(0xE6FFFFFF.toInt())
            includeFontPadding = false
        })
        addView(date)
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
        addView(clockBox, LayoutParams(-2, -2, Gravity.CENTER))
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
