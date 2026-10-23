package com.nello.tvhome

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Opens a [GlassPage] growing out of whatever is focused right now (the ⚙ pill, a settings row):
 * the page reads that rectangle and morphs from it, like the pop-ups.
 */
fun Activity.openPage(intent: Intent) {
    currentFocus?.takeIf { it.isAttachedToWindow }?.let { v ->
        val l = IntArray(2).also { v.getLocationOnScreen(it) }
        intent.putExtra(GlassPage.EXTRA_FROM, Rect(l[0], l[1], l[0] + (v.width * v.scaleX).toInt(), l[1] + (v.height * v.scaleY).toInt()))
    }
    startActivity(intent)
    overridePendingTransition(0, 0)
}

/**
 * Full-page screens (settings, details, standings) with the pop-ups' logic: a glass panel over
 * the launcher (dimmed, still visible behind), born from what you selected and folding back into it
 * on Back; one liquid selector pill sliding between rows; a slim scroll indicator.
 */
abstract class GlassPage : ComponentActivity() {

    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(UiScale.wrap(newBase))


    companion object {
        const val EXTRA_FROM = "glass_from"
    }

    protected lateinit var list: LiquidList
    private lateinit var panel: LiquidCard
    private lateinit var scroll: PageScroller
    private lateinit var dim: ColorDrawable
    private var from: RectF? = null
    private var morphAnim: android.animation.ValueAnimator? = null
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = LiquidList(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(28), dp(28), dp(36))
        }
        scroll = PageScroller(this).apply { addView(list) }
        panel = LiquidCard(this).apply { addView(scroll, FrameLayout.LayoutParams(-1, -1)) }
        val indicator = ScrollIndicator(this, scroll)
        dim = ColorDrawable(0xCC000000.toInt()).apply { alpha = 0 }
        val root = FrameLayout(this).apply {
            background = dim
            addView(panel, FrameLayout.LayoutParams(dp(560), -1).apply { setMargins(dp(24), dp(24), 0, dp(24)) })
            addView(indicator, FrameLayout.LayoutParams(dp(560), -1).apply { setMargins(dp(24), dp(24), 0, dp(24)) })
        }
        setContentView(root)
        from = intent.getParcelableExtra<Rect>(EXTRA_FROM)?.let { RectF(it) }
        open()
    }

    // ---------------------------------------------------------------- morph in / out

    private val ease = PathInterpolator(0.2f, 0.9f, 0.1f, 1f)
    private val fold = PathInterpolator(0.45f, 0f, 0.2f, 1f)
    private val origin = RectF()

    private fun shapeAt(t: Float) {
        val w = panel.width.toFloat(); val h = panel.height.toFloat()
        val o = origin
        fun mix(a: Float, b: Float, x: Float) = a + (b - a) * x
        val kb = (t * 1.12f).coerceAtMost(1f)
        val shape = RectF(mix(o.left, 0f, t), mix(o.top, 0f, t), mix(o.right, w, t), mix(o.bottom, h, kb))
        val white = if (from != null) (1f - t / 0.3f).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) } else 0f
        panel.setMorph(shape, mix(minOf(o.width(), o.height()) / 2f, panel.radius, t), white, 0f, o)
        scroll.alpha = ((t - 0.25f) / 0.45f).coerceIn(0f, 1f)
        dim.alpha = (t * 255).toInt().coerceIn(0, 255)
    }

    private fun open() {
        scroll.alpha = 0f
        panel.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                panel.viewTreeObserver.removeOnPreDrawListener(this)
                val p = IntArray(2).also { panel.getLocationOnScreen(it) }
                val f = from
                // the selected pill (in the panel's coordinates), or a smaller panel at its top
                if (f != null) origin.set(f.left - p[0], f.top - p[1], f.right - p[0], f.bottom - p[1])
                else origin.set(0f, 0f, panel.width * 0.6f, panel.height * 0.35f)
                shapeAt(0f)
                morphAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 860 // ≈430 ms at 0.5x
                    interpolator = ease
                    addUpdateListener { shapeAt(it.animatedValue as Float) }
                    start()
                }
                return true
            }
        })
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) close()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Back: the page folds into the pill it came from, then goes. */
    private fun close() {
        if (closing) return
        closing = true
        val at = (morphAnim?.animatedValue as? Float) ?: 1f
        morphAnim?.cancel()
        android.animation.ValueAnimator.ofFloat(at, 0f).apply {
            duration = (640 * at).toLong().coerceAtLeast(160)
            interpolator = fold
            addUpdateListener { shapeAt(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    finish()
                    @Suppress("DEPRECATION") overridePendingTransition(0, 0)
                }
            })
            start()
        }
    }

    // ---------------------------------------------------------------- content

    protected fun title(text: String, subtitle: String? = null) {
        list.addView(TextView(this).apply {
            this.text = text
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Fonts.medium
            setPadding(dp(14), 0, dp(14), 0)
        })
        if (subtitle != null) list.addView(TextView(this).apply {
            this.text = subtitle
            textSize = 13f
            setTextColor(0x99FFFFFF.toInt())
            setPadding(dp(14), dp(4), dp(14), 0)
        })
        list.addView(View(this), LinearLayout.LayoutParams(-1, dp(8)))
    }

    /** Section label, with a hairline above (like the groups in the pop-ups). */
    protected fun header(text: String) {
        list.addView(View(this).apply { setBackgroundColor(0x1FFFFFFF) }, LinearLayout.LayoutParams(-1, dp(1)).apply {
            setMargins(dp(14), dp(14), dp(14), 0)
        })
        list.addView(TextView(this).apply {
            this.text = text.uppercase()
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(0x80FFFFFF.toInt())
            setPadding(dp(14), dp(10), 0, dp(4))
        })
    }

    /** A focusable row; returns it so a pop-up can open under it. */
    protected fun item(title: String, value: String?, onClick: (View) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            // no background of its own: the list's pill slides behind the focused row
            background = null
            setPadding(dp(14), dp(11), dp(14), dp(11))
            setOnClickListener { onClick(it) }
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(0xF2FFFFFF.toInt())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (value != null) row.addView(TextView(this).apply {
            text = value
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(0x99FFFFFF.toInt())
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(-2, -2).apply { width = -2 })
        list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(1) })
        return row
    }

    /** A read-only "label · value" line; returns the value view to update it later. */
    protected fun info(label: String, value: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(7), dp(14), dp(7))
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(0x99FFFFFF.toInt())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val v = TextView(this).apply {
            text = value
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
        }
        row.addView(v)
        list.addView(row)
        return v
    }

    protected fun popup() = GlassPopup(this)

    protected fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

/**
 * The page's scroll: no system scrollbar, and it glides (soft ease-out) to keep the focused row
 * comfortably inside — a third of the way from either edge — instead of jumping to the edge.
 */
class PageScroller(context: android.content.Context) : ScrollView(context) {
    private var anim: android.animation.ValueAnimator? = null
    private var target = -1

    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = true
    }

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?): Int = 0

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        val f = focused ?: return
        post { follow(f) }
    }

    private fun follow(v: View) {
        val r = Rect()
        v.getDrawingRect(r)
        offsetDescendantRectToMyCoords(v, r)
        val margin = height / 3
        val y = when {
            r.top - scrollY < margin -> r.top - margin
            r.bottom - scrollY > height - margin -> r.bottom - height + margin
            else -> return
        }
        glideTo(y)
    }

    private fun glideTo(y: Int) {
        val max = ((getChildAt(0)?.height ?: 0) - height).coerceAtLeast(0)
        val to = y.coerceIn(0, max)
        if (to == scrollY || (to == target && anim?.isRunning == true)) return
        anim?.cancel()
        target = to
        anim = android.animation.ValueAnimator.ofInt(scrollY, to).apply {
            duration = (560 + Math.abs(to - scrollY) / 3).coerceAtMost(820).toLong()
            interpolator = PathInterpolator(0.2f, 0.9f, 0.25f, 1f)
            addUpdateListener { scrollTo(0, it.animatedValue as Int) }
            start()
        }
    }
}

/**
 * Slim scroll indicator in the launcher's style: a rounded translucent thumb on a faint track, at
 * the panel's right edge. Shows while the page moves, fades away a moment later; hidden when the
 * page fits.
 */
class ScrollIndicator(context: android.content.Context, private val scroll: ScrollView) : View(context) {
    private val dp = resources.displayMetrics.density
    private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x14FFFFFF }
    private val thumb = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x73FFFFFF }
    private val hide = Runnable { animate().alpha(0f).setDuration(700).start() }

    init {
        alpha = 0f
        isFocusable = false
        scroll.setOnScrollChangeListener { _, _, _, _, _ ->
            invalidate()
            removeCallbacks(hide)
            animate().cancel()
            animate().alpha(1f).setDuration(200).start()
            postDelayed(hide, 1400)
        }
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val content = scroll.getChildAt(0)?.height ?: return
        val view = scroll.height
        if (content <= view) return
        val w = 3 * dp
        val x = width - 10 * dp
        val top = 24 * dp
        val len = height - 48 * dp
        canvas.drawRoundRect(x, top, x + w, top + len, w, w, track)
        val tLen = (len * view / content).coerceAtLeast(28 * dp)
        val tTop = top + (len - tLen) * scroll.scrollY / (content - view)
        canvas.drawRoundRect(x, tTop, x + w, tTop + tLen, w, w, thumb)
    }
}
