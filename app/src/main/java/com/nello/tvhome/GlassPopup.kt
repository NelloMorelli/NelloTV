package com.nello.tvhome

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * The launcher's pop-up: a glass card that opens right under what you selected,
 * with the essentials (title, a few info rows) and buttons — "Dettagli" leads to a full page.
 */
class GlassPopup(private val context: Context, widthDp: Int = 300) {

    private val dp = context.resources.displayMetrics.density
    private val body = LiquidList(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(12), px(12), px(12), px(10))
    }
    /** long lists (e.g. the catalogue picker) scroll inside the card instead of running off screen */
    private val scroller = android.widget.ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        addView(body, ViewGroup.LayoutParams(-1, -2))
    }
    private val card = LiquidCard(context).apply {
        addView(scroller, android.widget.FrameLayout.LayoutParams(-1, -2))
    }
    /** the button it came out of (↑ on the first row goes back there) */
    private var anchor: View? = null
    /** set while the spotlight is open: closing plays its exit animation first */
    private var exitAnimation: ((done: () -> Unit) -> Unit)? = null

    private val window = object : PopupWindow(card, px(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT, true) {
        private var closing = false
        override fun dismiss() {
            val exit = exitAnimation
            if (exit == null || closing) return super.dismiss()
            closing = true
            exit { super.dismiss() }
        }
    }.apply {
        setBackgroundDrawable(ColorDrawable(0))
        elevation = 24 * dp
        animationStyle = android.R.style.Animation_Dialog
        isOutsideTouchable = true
    }
    private var firstButton: View? = null

    var onDismiss: (() -> Unit)? = null

    val isShowing get() = window.isShowing

    fun title(text: String, subtitle: String? = null) = apply {
        body.addView(TextView(context).apply {
            this.text = text
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(px(6), 0, px(6), 0)
        })
        if (subtitle != null) body.addView(TextView(context).apply {
            this.text = subtitle
            textSize = 12f
            setTextColor(0x99FFFFFF.toInt())
            setPadding(px(6), px(2), px(6), 0)
        })
        body.addView(View(context), LinearLayout.LayoutParams(-1, px(8)))
    }

    /**
     * The pop-up's reason to be there: what that button is about right now, at a glance —
     * a round glass bubble with its icon, the state in big, a detail underneath.
     * Returns the detail line, to fill in later (ping, IP…).
     */
    fun hero(kind: StatusIcon.Kind, state: String, detail: String, level: Int = 3, active: Boolean = true): TextView {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(4), px(2), px(4), px(10))
        }
        val bubble = android.widget.FrameLayout(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (active) 0x33FFFFFF else 0x14FFFFFF)
                setStroke(px(1), 0x40FFFFFF)
            }
            addView(StatusIcon(context).apply { this.kind = kind; this.level = level }, android.widget.FrameLayout.LayoutParams(px(24), px(24), Gravity.CENTER))
        }
        row.addView(bubble, LinearLayout.LayoutParams(px(44), px(44)).apply { marginEnd = px(12) })
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(context).apply {
            text = state
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val d = TextView(context).apply {
            text = detail
            textSize = 12f
            setTextColor(0x99FFFFFF.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        col.addView(d)
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(row)
        return d
    }

    /** A "label · value" row; returns the value view so it can be filled in later. */
    fun info(label: String, value: String): TextView {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(6), px(3), px(6), px(3))
        }
        row.addView(TextView(context).apply {
            text = label
            textSize = 12.5f
            setTextColor(context.getColor(R.color.text_dim))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val v = TextView(context).apply {
            text = value
            textSize = 12.5f
            gravity = Gravity.END
            setTextColor(0xFFFFFFFF.toInt())
        }
        row.addView(v)
        body.addView(row)
        return v
    }

    fun text(text: String) = apply {
        body.addView(TextView(context).apply {
            this.text = text
            textSize = 13f
            setLineSpacing(0f, 1.15f)
            setTextColor(context.getColor(R.color.text_dim))
            setPadding(px(4), 0, px(4), px(6))
        })
    }

    fun view(v: View, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) = apply {
        body.addView(v, LinearLayout.LayoutParams(-1, height))
    }

    /** a hairline between groups */
    fun gap() = apply {
        body.addView(View(context).apply { setBackgroundColor(0x1FFFFFFF) }, LinearLayout.LayoutParams(-1, px(1)).apply {
            setMargins(px(8), px(6), px(8), px(6))
        })
    }

    /** A pill button; by default it closes the pop-up after running. */
    fun button(text: String, keepOpen: Boolean = false, action: () -> Unit): TextView {
        // "✓  Name" marks the chosen option: shown as a small dot at the end instead of a tick
        val selected = text.startsWith("✓")
        val b = TextView(context).apply {
            this.text = text.removePrefix("✓").trim()
            if (selected) {
                // white here, dark when the pill is over it (the list re-inks what's under the pill)
                setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(android.graphics.Color.WHITE)
                    setSize(px(7), px(7))
                }, null)
                compoundDrawablePadding = px(10)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }
            textSize = 14f
            isFocusable = true
            isClickable = true
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(14), 0, px(14), 0)
            // no background of its own: the list's liquid pill slides behind the focused one
            background = null
            setTextColor(0xE6FFFFFF.toInt())
            setOnClickListener {
                if (!keepOpen) dismiss()
                action()
            }
            setOnFocusChangeListener { v, has -> if (has) body.pillTo(v) }
            setOnKeyListener { v, code, e ->
                // ↑ on the first row: back into the button it came from, no Back needed
                if (code == android.view.KeyEvent.KEYCODE_DPAD_UP && v === firstButton && anchor != null) {
                    if (e.action == android.view.KeyEvent.ACTION_DOWN) closeToAnchor()
                    true
                } else false
            }
        }
        body.addView(b, LinearLayout.LayoutParams(-1, px(38)).apply { topMargin = px(1) })
        if (firstButton == null) firstButton = b
        return b
    }

    /** Opens under [anchor], right-aligned with it when [alignEnd]. */
    fun showUnder(anchor: View, alignEnd: Boolean = true) {
        prepare()
        this.anchor = anchor
        // the window starts at the button's top: the card is drawn from the button down to its place
        val gapTop = anchor.height + px(10)
        card.contentTop = gapTop
        (scroller.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin = gapTop
        card.measure(View.MeasureSpec.makeMeasureSpec(window.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val available = context.resources.displayMetrics.heightPixels - loc[1] - px(24)
        window.height = if (card.measuredHeight > available) available else ViewGroup.LayoutParams.WRAP_CONTENT
        window.animationStyle = 0
        window.elevation = 0f
        val x = if (alignEnd) anchor.width - window.width else 0
        window.showAsDropDown(anchor, x, -anchor.height)
        focusFirst()
        pourFrom(anchor, alignEnd)
    }

    // ---------------------------------------------------------------- liquid open / close
    // The pill turns into the pop-up. Frame one is an exact copy of the focused button (its white
    // and its icon, from a snapshot), so nothing jumps when the window appears; then a single
    // continuous motion stretches it into the card while the white slowly melts into glass and the
    // icon fades in the corner it rides along. Closing runs it backwards and ends on the button.

    private val morph = android.view.animation.PathInterpolator(0.32f, 0.72f, 0.0f, 1f)
    private var shapeAnim: android.animation.ValueAnimator? = null
    private var from = android.graphics.RectF()
    private var fromRadius = 0f

    private fun shapeAt(t: Float) {
        val to = android.graphics.RectF(0f, card.contentTop.toFloat(), card.width.toFloat(), card.height.toFloat())
        val k = morph.getInterpolation(t)
        // the bottom runs a touch ahead of the sides: it reads as flowing out of the pill
        val kb = morph.getInterpolation((t * 1.12f).coerceAtMost(1f))
        fun mix(a: Float, b: Float, x: Float) = a + (b - a) * x
        val shape = android.graphics.RectF(mix(from.left, to.left, k), mix(from.top, to.top, k), mix(from.right, to.right, k), mix(from.bottom, to.bottom, kb))
        card.shape = shape
        card.shapeRadius = mix(fromRadius, card.radius, k)
        // white → glass over the first 35%, smoothly (glass by the time it's big)
        val w = (1f - t / 0.35f).coerceIn(0f, 1f)
        card.whiteness = w * w * (3 - 2 * w)
        // the icon stays pinned to the shape's corner on the button's side, fading out early
        card.iconAlpha = (1f - t / 0.25f).coerceIn(0f, 1f)
        card.iconRect.set(shape.right - from.width(), shape.top, shape.right, shape.top + from.height())
    }

    private fun pourFrom(anchor: View, @Suppress("UNUSED_PARAMETER") alignEnd: Boolean) {
        scroller.alpha = 0f
        card.shape = android.graphics.RectF()
        // the button exactly as it looks now (focused: white with the dark icon)
        card.icon = runCatching {
            android.graphics.Bitmap.createBitmap(anchor.width, anchor.height, android.graphics.Bitmap.Config.ARGB_8888).also {
                anchor.draw(android.graphics.Canvas(it))
            }
        }.getOrNull()
        card.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                card.viewTreeObserver.removeOnPreDrawListener(this)
                val a = IntArray(2).also { anchor.getLocationOnScreen(it) }
                val c = IntArray(2).also { card.getLocationOnScreen(it) }
                val w = anchor.width * anchor.scaleX
                val h = anchor.height * anchor.scaleY
                // getLocationOnScreen already includes the focus zoom around the centre
                from = android.graphics.RectF(a[0] - c[0].toFloat(), a[1] - c[1].toFloat(), a[0] - c[0] + w, a[1] - c[1] + h)
                fromRadius = minOf(w, h) / 2f
                shapeAt(0f)
                // the window's first frame is slow: start once it's really on screen (until then
                // it looks exactly like the button, so the wait is invisible)
                card.postOnAnimation { card.postOnAnimation {
                    shapeAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 1100 // ≈550 ms at 0.5x
                        interpolator = android.view.animation.LinearInterpolator()
                        addUpdateListener { shapeAt(it.animatedValue as Float) }
                        start()
                    }
                    scroller.translationY = -px(8).toFloat()
                    scroller.animate().alpha(1f).translationY(0f).setStartDelay(420).setDuration(640)
                        .setInterpolator(android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f)).start()
                } }
                return true
            }
        })
        exitAnimation = { done ->
            val at = (shapeAnim?.animatedValue as? Float) ?: 1f
            shapeAnim?.cancel()
            scroller.animate().cancel()
            scroller.animate().alpha(0f).setStartDelay(0).setDuration(200).start()
            android.animation.ValueAnimator.ofFloat(at, 0f).apply {
                duration = (820 * at).toLong().coerceAtLeast(200)
                interpolator = android.view.animation.PathInterpolator(0.5f, 0f, 0.2f, 1f)
                addUpdateListener { shapeAt(it.animatedValue as Float) }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    // back to an exact copy of the button: the real one takes over invisibly
                    override fun onAnimationEnd(a: android.animation.Animator) { card.postOnAnimation { done() } }
                })
                start()
            }
        }
    }

    /** ↑ from the first row: close and give the focus back to the button. */
    private fun closeToAnchor() {
        val a = anchor ?: return
        a.requestFocus()
        dismiss()
    }

    fun showCentered(parent: View) {
        prepare()
        window.showAtLocation(parent, Gravity.CENTER, 0, 0)
        focusFirst()
    }

    /**
     * Spotlight: the screen behind is blurred and dimmed, [hero] (e.g. the app's banner, enlarged)
     * sits in the middle and the menu card next to it — always fully on screen.
     */
    /** [source]: the app's tile on screen; the hero flies out of it and back into it. */
    fun showSpotlight(screen: View, hero: View, heroWidthDp: Int, heroHeightDp: Int, source: View? = null) {
        prepare()
        // posters are hardware bitmaps, so the screen is captured with PixelCopy rather than View.draw
        val activity = context as android.app.Activity
        val shot = android.graphics.Bitmap.createBitmap(480, 270, android.graphics.Bitmap.Config.ARGB_8888)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        android.view.PixelCopy.request(activity.window, shot, { result ->
            // blur off the main thread, so the opening animation isn't stalled
            Thread {
                val blur = if (result == android.view.PixelCopy.SUCCESS) GlassView.makeBlur(shot) else GlassView.blurred
                main.post { openSpotlight(screen, hero, heroWidthDp, heroHeightDp, blur, source) }
            }.start()
        }, main)
    }

    private fun openSpotlight(screen: View, hero: View, heroWidthDp: Int, heroHeightDp: Int, blur: android.graphics.Bitmap?, source: View?) {
        // the app on top, its menu right underneath, both centred
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // the tile flies in from outside this box
            clipChildren = false
            clipToPadding = false
            addView(hero, LinearLayout.LayoutParams(px(heroWidthDp), px(heroHeightDp)).apply { bottomMargin = px(18) })
            (card.parent as? ViewGroup)?.removeView(card)
            addView(card, LinearLayout.LayoutParams(window.width, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val root = android.widget.FrameLayout(context).apply {
            // the screen behind, blurred and dimmed, so only the app and its menu stand out
            background = android.graphics.drawable.LayerDrawable(listOfNotNull(
                blur?.let { android.graphics.drawable.BitmapDrawable(context.resources, it) },
                ColorDrawable(0x80000000.toInt()),
            ).toTypedArray())
            // anchored from the top (not centred): the tile's landing spot doesn't move while the
            // menu below it takes its size
            addView(row, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = (context.resources.displayMetrics.heightPixels * 0.13f).toInt()
            })
            clipChildren = false
        }
        window.contentView = root
        window.width = ViewGroup.LayoutParams.MATCH_PARENT
        window.height = ViewGroup.LayoutParams.MATCH_PARENT
        window.elevation = 0f
        // the animation is ours (below), not the window's
        window.animationStyle = 0
        window.showAtLocation(screen, Gravity.NO_GRAVITY, 0, 0)
        focusFirst()

        // entrance, ease-out (durations doubled: the Fire TV runs animations at 0.5x).
        // Start state now, motion on the frame after the window is first drawn.
        val easeOut = android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f)
        val bg = root.background
        bg.alpha = 0
        card.alpha = 0f
        card.translationY = px(36).toFloat()

        // the source tile's rect on screen (getLocationOnScreen includes its focus zoom)
        val src = source?.takeIf { it.isAttachedToWindow && it.width > 0 }?.let {
            val l = IntArray(2).also { a -> it.getLocationOnScreen(a) }
            android.graphics.RectF(l[0].toFloat(), l[1].toFloat(), l[0] + it.width * it.scaleX, l[1] + it.height * it.scaleY)
        }
        /**
         * Flight between the source tile and the hero's own place, as progress t (0 = over the
         * source, 1 = in place). The offset is recomputed every frame from where the hero is laid
         * out right now, so the path stays right even if the popup's layout settles mid-flight.
         */
        val loc = IntArray(2)
        fun flyTo(t: Float) {
            val r = src ?: return
            if (hero.width == 0) return
            hero.getLocationOnScreen(loc)
            // layout position = screen position minus current translation
            val baseX = loc[0] - hero.translationX
            val baseY = loc[1] - hero.translationY
            val u = 1f - t
            hero.translationX = (r.left - baseX) * u
            hero.translationY = (r.top - baseY) * u
            hero.scaleX = 1f + (r.width() / hero.width - 1f) * u
            hero.scaleY = 1f + (r.height() / hero.height - 1f) * u
        }
        hero.pivotX = 0f
        hero.pivotY = 0f
        if (src == null) {
            hero.alpha = 0f
            hero.scaleX = 0.82f; hero.scaleY = 0.82f
            hero.pivotX = px(heroWidthDp) / 2f; hero.pivotY = px(heroHeightDp) / 2f
        } else {
            hero.visibility = View.INVISIBLE
        }

        root.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                root.viewTreeObserver.removeOnPreDrawListener(this)
                if (src != null) {
                    flyTo(0f)
                    hero.visibility = View.VISIBLE
                    // the app has "lifted off" its spot
                    source?.alpha = 0f
                }
                // the popup's first frame is slow (~100 ms): start the clock only once it's on
                // screen, or the first part of the flight is skipped
                root.postOnAnimation { root.postOnAnimation {
                    android.animation.ValueAnimator.ofInt(0, 255).apply {
                        duration = 480
                        interpolator = easeOut
                        addUpdateListener { bg.alpha = it.animatedValue as Int }
                        start()
                    }
                    if (src != null) android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 720
                        interpolator = easeOut
                        addUpdateListener { flyTo(it.animatedValue as Float) }
                        start()
                    } else {
                        hero.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(720).setInterpolator(easeOut).start()
                    }
                    card.animate().alpha(1f).translationY(0f)
                        .setStartDelay(160).setDuration(700).setInterpolator(easeOut).start()
                } }
                return false
            }
        })

        // exit: the menu fades, the tile flies back into its spot (or shrinks away)
        exitAnimation = { done ->
            val easeInOut = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
            android.animation.ValueAnimator.ofInt(bg.alpha, 0).apply {
                duration = 480
                interpolator = easeInOut
                addUpdateListener { bg.alpha = it.animatedValue as Int }
                start()
            }
            card.animate().alpha(0f).translationY(px(16).toFloat()).setStartDelay(0).setDuration(260)
                .setInterpolator(android.view.animation.AccelerateInterpolator(1.5f)).start()
            // landed: show the real tile first, close the popup on the next frame (no empty-spot flash)
            val finish = {
                source?.alpha = 1f
                root.postOnAnimation { done() }
            }
            if (src != null && source?.isAttachedToWindow == true) {
                android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
                    duration = 520
                    interpolator = easeInOut
                    addUpdateListener { flyTo(it.animatedValue as Float) }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(a: android.animation.Animator) { finish() }
                    })
                    start()
                }
            } else {
                hero.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(300)
                    .setInterpolator(android.view.animation.AccelerateInterpolator(1.5f)).withEndAction { finish() }.start()
            }
        }
        // whatever closes it, the app tile comes back
        val prevDismiss = onDismiss
        onDismiss = {
            source?.alpha = 1f
            prevDismiss?.invoke()
        }
        window.setOnDismissListener { onDismiss?.invoke() }
    }

    fun dismiss() {
        if (window.isShowing) window.dismiss()
    }

    private fun prepare() {
        window.setOnDismissListener { onDismiss?.invoke() }
    }

    private fun focusFirst() {
        firstButton?.post { firstButton?.requestFocus() }
    }

    private fun px(v: Int) = (v * dp).toInt()
}

/**
 * The pop-up's glass: the blurred wallpaper (like every glass surface in the launcher) with the
 * frosted gradient and a hairline border, drawn inside [shape] — which the pop-up animates, so the
 * card can pour out of a button and drain back into it. Children are clipped to the same shape.
 */
class LiquidCard(context: Context) : android.widget.FrameLayout(context) {
    private val dp = resources.displayMetrics.density
    val radius = 20 * dp
    /** where the card's own area starts (above it: the button it comes out of) */
    var contentTop = 0
    var shape: android.graphics.RectF? = null
        set(v) { field = v; invalidate() }
    var shapeRadius = radius
    /** 0..1: how much it still looks like the white focused button it grows out of */
    var whiteness = 0f
    /** the button's snapshot, drawn in [iconRect] at [iconAlpha] */
    var icon: android.graphics.Bitmap? = null
    var iconAlpha = 0f
    val iconRect = android.graphics.RectF()
    private val iconPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val path = android.graphics.Path()
    private val rect = android.graphics.RectF()
    private val loc = IntArray(2)
    private val glass = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val frost = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val border = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = dp
        color = 0x4DFFFFFF
    }
    private val matrix = android.graphics.Matrix()
    private var shaderFor: android.graphics.Bitmap? = null

    init {
        setWillNotDraw(false)
        clipChildren = true
    }

    private fun current(): Float {
        val s = shape
        if (s != null) { rect.set(s); return shapeRadius }
        rect.set(0f, contentTop.toFloat(), width.toFloat(), height.toFloat())
        return radius
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val r = minOf(current(), rect.height() / 2f, rect.width() / 2f)
        if (rect.width() <= 0f || rect.height() <= 0f) return
        GlassView.blurred?.let { b ->
            if (shaderFor !== b) {
                glass.shader = android.graphics.BitmapShader(b, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                shaderFor = b
            }
            getLocationOnScreen(loc)
            val dm = resources.displayMetrics
            matrix.setScale(dm.widthPixels.toFloat() / b.width, dm.heightPixels.toFloat() / b.height)
            matrix.postTranslate(-loc[0].toFloat(), -loc[1].toFloat())
            glass.shader.setLocalMatrix(matrix)
            canvas.drawRoundRect(rect, r, r, glass)
        }
        frost.shader = android.graphics.LinearGradient(0f, rect.top, 0f, rect.bottom,
            if (GlassView.night) intArrayOf(0x1FFFFFFF, 0x0FFFFFFF, 0x08FFFFFF) else Theme.frostColors(),
            floatArrayOf(0f, 0.45f, 1f), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, r, r, frost)
        // a darker veil so text stays readable on bright wallpapers
        frost.shader = null
        frost.color = 0x59000000
        canvas.drawRoundRect(rect, r, r, frost)
        if (whiteness > 0f) {
            frost.color = ((whiteness * 0xF2).toInt() shl 24) or (Theme.accent and 0xFFFFFF)
            canvas.drawRoundRect(rect, r, r, frost)
        }
        border.alpha = ((1f - whiteness) * 0x4D).toInt()
        val h = border.strokeWidth / 2
        canvas.drawRoundRect(rect.left + h, rect.top + h, rect.right - h, rect.bottom - h, r, r, border)
        icon?.let { b ->
            if (iconAlpha <= 0f) return@let
            // inked like under the selector pill: the button's own icon is white, the pill darkens it
            val c = Theme.onAccent
            iconPaint.colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(
                0f, 0f, 0f, 0f, android.graphics.Color.red(c).toFloat(),
                0f, 0f, 0f, 0f, android.graphics.Color.green(c).toFloat(),
                0f, 0f, 0f, 0f, android.graphics.Color.blue(c).toFloat(),
                0f, 0f, 0f, 2.5f, 0f,
            ))
            iconPaint.alpha = (iconAlpha * 255).toInt()
            canvas.save()
            canvas.clipPath(android.graphics.Path().apply { addRoundRect(rect, r, r, android.graphics.Path.Direction.CW) })
            canvas.drawBitmap(b, null, iconRect, iconPaint)
            canvas.restore()
        }
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        val r = minOf(current(), rect.height() / 2f, rect.width() / 2f)
        if (rect.width() <= 0f || rect.height() <= 0f) return
        path.reset()
        path.addRoundRect(rect, r, r, android.graphics.Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path)
        super.dispatchDraw(canvas)
        canvas.restore()
    }
}

/**
 * The pop-up's rows, with the launcher's liquid selector: one pill slides from row to row (its
 * front edge leading, the back catching up with a little wobble), and whatever is under it is
 * re-drawn dark — so a row turns dark exactly where the pill is, mid-slide included.
 */
class LiquidList(context: Context) : LinearLayout(context) {
    private val dp = resources.displayMetrics.density
    private val pill = android.graphics.RectF()
    private val start = android.graphics.RectF()
    private val end = android.graphics.RectF()
    private var shown = false
    private var grow = 1f
    private var anim: android.animation.ValueAnimator? = null
    private val lead = android.view.animation.PathInterpolator(0.25f, 0.9f, 0.3f, 1f)
    private val trail = android.view.animation.PathInterpolator(0.5f, 0f, 0.25f, 1.12f)
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val ink = android.graphics.Paint()
    private val clip = android.graphics.Path()

    init { setWillNotDraw(false) }

    fun pillTo(v: View) {
        end.set(v.left.toFloat(), v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat())
        anim?.cancel()
        if (!shown) {
            // first time: it grows out of the row's centre
            shown = true
            pill.set(end)
            anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 520
                interpolator = lead
                addUpdateListener { grow = it.animatedValue as Float; invalidate() }
                start()
            }
            return
        }
        start.set(pill)
        val down = end.top > start.top
        anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 560 // ≈280 ms at 0.5x
            addUpdateListener {
                val t = it.animatedValue as Float
                val f = lead.getInterpolation(t)
                val b = trail.getInterpolation(t)
                // the edge in the direction of travel leads
                val top = start.top + (end.top - start.top) * (if (down) b else f)
                val bottom = start.bottom + (end.bottom - start.bottom) * (if (down) f else b)
                pill.set(end.left, top, end.right, bottom)
                invalidate()
            }
            start()
        }
    }

    private fun current(): android.graphics.RectF {
        if (grow >= 1f) return pill
        val cx = pill.centerX(); val cy = pill.centerY()
        val hw = pill.width() / 2 * (0.3f + 0.7f * grow); val hh = pill.height() / 2 * (0.5f + 0.5f * grow)
        return android.graphics.RectF(cx - hw, cy - hh, cx + hw, cy + hh)
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        if (!shown || !hasFocus()) { super.dispatchDraw(canvas); return }
        val r = current()
        val radius = r.height() / 2
        paint.color = (((0xF2 * minOf(1f, grow * 1.4f)).toInt()) shl 24) or (Theme.accent and 0xFFFFFF)
        canvas.drawRoundRect(r, radius, radius, paint)
        super.dispatchDraw(canvas)
        // the rows again in the "on accent" colour, only inside the pill
        val c = Theme.onAccent
        ink.colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(
            0f, 0f, 0f, 0f, android.graphics.Color.red(c).toFloat(),
            0f, 0f, 0f, 0f, android.graphics.Color.green(c).toFloat(),
            0f, 0f, 0f, 0f, android.graphics.Color.blue(c).toFloat(),
            0f, 0f, 0f, 2.5f, 0f,
        ))
        clip.reset()
        clip.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.saveLayer(r, ink)
        super.dispatchDraw(canvas)
        canvas.restore()
        canvas.restore()
    }
}
