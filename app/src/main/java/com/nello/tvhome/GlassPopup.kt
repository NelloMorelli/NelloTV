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

    val isShowing get() = window.isShowing || overlay != null

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
     * The pop-up's reason to be there: what that button is about right now, at a glance — the
     * state in big, a detail underneath (the button it came out of already says what it is).
     * Returns the detail line, to fill in later (ping, IP…).
     */
    fun hero(state: String, detail: String): TextView {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(6), px(2), px(6), px(10))
        }
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
        body.addView(col)
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

    // ---------------------------------------------------------------- under a button: in-screen
    // No separate window (its first frame alone takes ~100 ms): the card is an overlay inside the
    // launcher's own screen, so the pill starts moving on the very frame OK is pressed. Focus is
    // kept inside the overlay, Back closes it.

    private val widthPx = px(widthDp)
    private var overlay: android.widget.FrameLayout? = null
    private var closing = false

    /** Opens under [anchor], right-aligned with it when [alignEnd]. */
    fun showUnder(anchor: View, alignEnd: Boolean = true) {
        val activity = context as? android.app.Activity ?: return
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        this.anchor = anchor
        val a = IntArray(2).also { anchor.getLocationInWindow(it) }
        val c = IntArray(2).also { content.getLocationInWindow(it) }
        val ax = a[0] - c[0]; val ay = a[1] - c[1]
        // the card's area starts at the button's top: the pill grows down from there into place
        val gapTop = anchor.height + px(10)
        card.contentTop = gapTop
        (scroller.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin = gapTop
        card.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val available = content.height - ay - px(24)
        val height = minOf(card.measuredHeight, available)
        val left = if (alignEnd) ax + anchor.width - widthPx else ax
        val root = object : android.widget.FrameLayout(context) {
            // focus never leaves the card
            override fun focusSearch(focused: View?, direction: Int): View? {
                val next = super.focusSearch(focused, direction)
                return if (next != null && isInside(next)) next else focused
            }
            private fun isInside(v: View): Boolean {
                var p: android.view.ViewParent? = v.parent
                while (p != null) { if (p === this) return true; p = p.parent }
                return false
            }
            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    if (event.action == android.view.KeyEvent.ACTION_UP) closeToAnchor()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        root.clipChildren = false
        (card.parent as? ViewGroup)?.removeView(card)
        root.addView(card, android.widget.FrameLayout.LayoutParams(widthPx, height).apply {
            leftMargin = left.coerceAtLeast(px(8)); topMargin = ay
        })
        content.addView(root, ViewGroup.LayoutParams(-1, -1))
        overlay = root
        focusFirst()
        morphFrom(anchor)
    }

    // ---------------------------------------------------------------- the morph
    // The pill itself turns into the pop-up. The card is laid out complete from the start and the
    // pill's shape — a GPU outline clip, nothing redrawn — opens up over it: first it's exactly the
    // focused button (white, dark icon), then it stretches into the card, the white melting into
    // glass in the first third, revealing the content as it grows. Closing folds it back.

    private val open = android.view.animation.PathInterpolator(0.2f, 0.9f, 0.1f, 1f)
    private val fold = android.view.animation.PathInterpolator(0.45f, 0f, 0.2f, 1f)
    private var shapeAnim: android.animation.ValueAnimator? = null
    private var from = android.graphics.RectF()
    private var fromRadius = 0f
    private val shapeRect = android.graphics.RectF()

    private fun shapeAt(t: Float) {
        val toTop = card.contentTop.toFloat()
        val k = t
        // the bottom runs a little ahead of the sides, like something poured
        val kb = (t * 1.15f).coerceAtMost(1f)
        fun mix(a: Float, b: Float, x: Float) = a + (b - a) * x
        shapeRect.set(mix(from.left, 0f, k), mix(from.top, toTop, k), mix(from.right, card.width.toFloat(), k), mix(from.bottom, card.height.toFloat(), kb))
        val w = (1f - t / 0.3f).coerceIn(0f, 1f)
        card.setMorph(shapeRect, mix(fromRadius, card.radius, k), w * w * (3 - 2 * w), (1f - t / 0.2f).coerceIn(0f, 1f), from)
        // content shows through as the glass forms
        scroller.alpha = ((t - 0.25f) / 0.45f).coerceIn(0f, 1f)
    }

    private fun morphFrom(anchor: View) {
        card.icon = runCatching {
            android.graphics.Bitmap.createBitmap(anchor.width, anchor.height, android.graphics.Bitmap.Config.ARGB_8888).also {
                anchor.draw(android.graphics.Canvas(it))
            }
        }.getOrNull()
        scroller.alpha = 0f
        card.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                card.viewTreeObserver.removeOnPreDrawListener(this)
                val a = IntArray(2).also { anchor.getLocationInWindow(it) }
                val c = IntArray(2).also { card.getLocationInWindow(it) }
                // includes the button's focus zoom
                val w = anchor.width * anchor.scaleX
                val h = anchor.height * anchor.scaleY
                val l = a[0] - c[0] + (anchor.width - w) / 2f * 0f
                from = android.graphics.RectF(l, a[1] - c[1].toFloat(), l + w, a[1] - c[1] + h)
                fromRadius = minOf(w, h) / 2f
                shapeAt(0f)
                shapeAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 760 // ≈380 ms at 0.5x
                    interpolator = open
                    addUpdateListener { shapeAt(it.animatedValue as Float) }
                    start()
                }
                return true
            }
        })
    }

    private fun closeOverlay(done: () -> Unit) {
        if (closing) return
        closing = true
        val root = overlay ?: return done()
        val at = (shapeAnim?.animatedValue as? Float) ?: 1f
        shapeAnim?.cancel()
        android.animation.ValueAnimator.ofFloat(at, 0f).apply {
            duration = (600 * at).toLong().coerceAtLeast(160)
            interpolator = fold
            addUpdateListener { shapeAt(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    // it's an exact copy of the button again: the real one takes over invisibly
                    (root.parent as? ViewGroup)?.removeView(root)
                    overlay = null
                    done()
                }
            })
            start()
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
        if (overlay != null) {
            // the focus must not be left inside a view that's going away
            anchor?.requestFocus()
            closeOverlay { onDismiss?.invoke() }
            return
        }
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
    private val rect = android.graphics.RectF()
    private var r = radius
    private var morphing = false
    private var whiteness = 0f
    private var iconAlpha = 0f
    private val iconRect = android.graphics.RectF()
    /** the button's snapshot, drawn while the pill is still a pill */
    var icon: android.graphics.Bitmap? = null
    private val loc = IntArray(2)
    private val glass = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val frost = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val border = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = dp
    }
    private val iconPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val matrix = android.graphics.Matrix()
    private var shaderFor: android.graphics.Bitmap? = null
    private var frostFor = -1

    init {
        setWillNotDraw(false)
        // the shape clips everything (children included) on the GPU: cheap to change every frame
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                current()
                outline.setRoundRect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt(), r)
            }
        }
        clipToOutline = true
    }

    /** One frame of the morph: [shape] with corner [radius], how white it still is, the icon's fade. */
    fun setMorph(shape: android.graphics.RectF, radius: Float, white: Float, iconFade: Float, iconAt: android.graphics.RectF) {
        morphing = true
        rect.set(shape); r = minOf(radius, shape.height() / 2f, shape.width() / 2f)
        whiteness = white; iconAlpha = iconFade
        // the icon rides in the pill's corner on the button's side
        iconRect.set(shape.right - iconAt.width(), shape.top, shape.right, shape.top + iconAt.height())
        invalidateOutline()
        invalidate()
    }

    private fun current() {
        if (morphing) return
        rect.set(0f, contentTop.toFloat(), width.toFloat(), height.toFloat()); r = radius
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        current()
        if (rect.width() <= 1f || rect.height() <= 1f) return
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
        // the frosted gradient spans the whole card (built once per size), the shape just reveals it
        if (frostFor != height) {
            frostFor = height
            frost.shader = android.graphics.LinearGradient(0f, contentTop.toFloat(), 0f, height.toFloat(),
                if (GlassView.night) intArrayOf(0x1FFFFFFF, 0x0FFFFFFF, 0x08FFFFFF) else Theme.frostColors(),
                floatArrayOf(0f, 0.45f, 1f), android.graphics.Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(rect, r, r, frost)
        // a darker veil so text stays readable on bright wallpapers
        fill.color = 0x59000000
        canvas.drawRoundRect(rect, r, r, fill)
        if (whiteness > 0f) {
            fill.color = ((whiteness * 0xF2).toInt() shl 24) or (Theme.accent and 0xFFFFFF)
            canvas.drawRoundRect(rect, r, r, fill)
        }
        border.color = (((1f - whiteness) * 0x4D).toInt() shl 24) or 0xFFFFFF
        val h = border.strokeWidth / 2
        canvas.drawRoundRect(rect.left + h, rect.top + h, rect.right - h, rect.bottom - h, r, r, border)
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        val b = icon ?: return
        if (iconAlpha <= 0f) return
        // inked like under the selector pill: the button's own icon is white, the pill darkens it
        if (iconPaint.colorFilter == null) {
            val c = Theme.onAccent
            iconPaint.colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(
                0f, 0f, 0f, 0f, android.graphics.Color.red(c).toFloat(),
                0f, 0f, 0f, 0f, android.graphics.Color.green(c).toFloat(),
                0f, 0f, 0f, 0f, android.graphics.Color.blue(c).toFloat(),
                0f, 0f, 0f, 2.5f, 0f,
            ))
        }
        iconPaint.alpha = (iconAlpha * 255).toInt()
        canvas.drawBitmap(b, null, iconRect, iconPaint)
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
