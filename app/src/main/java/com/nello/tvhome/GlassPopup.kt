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
class GlassPopup(private val context: Context, widthDp: Int = 340) {

    private val dp = context.resources.displayMetrics.density
    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(18), px(16), px(18), px(14))
    }
    /** long lists (e.g. the catalogue picker) scroll inside the card instead of running off screen */
    private val scroller = android.widget.ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        addView(body, ViewGroup.LayoutParams(-1, -2))
    }
    private val card = GlassView(context).apply {
        zoomOnFocus = false
        addView(scroller, ViewGroup.LayoutParams(-1, -2))
    }
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
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFFFFFFF.toInt())
        })
        if (subtitle != null) body.addView(TextView(context).apply {
            this.text = subtitle
            textSize = 12f
            setTextColor(context.getColor(R.color.accent))
            setPadding(0, px(2), 0, 0)
        })
        body.addView(View(context), LinearLayout.LayoutParams(-1, px(10)))
    }

    /** A "label · value" row; returns the value view so it can be filled in later. */
    fun info(label: String, value: String): TextView {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(4), px(3), px(4), px(3))
        }
        row.addView(TextView(context).apply {
            text = label
            textSize = 13f
            setTextColor(context.getColor(R.color.text_dim))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val v = TextView(context).apply {
            text = value
            textSize = 13f
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

    fun gap() = apply { body.addView(View(context), LinearLayout.LayoutParams(-1, px(8))) }

    /** A pill button; by default it closes the pop-up after running. */
    fun button(text: String, keepOpen: Boolean = false, action: () -> Unit): TextView {
        // "✓  Name" marks the chosen option: shown as a small dot at the end instead of a tick
        val selected = text.startsWith("✓")
        val b = TextView(context).apply {
            this.text = text.removePrefix("✓").trim()
            if (selected) {
                setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(android.graphics.Color.WHITE)
                    setSize(px(8), px(8))
                }, null)
                compoundDrawablePadding = px(10)
                compoundDrawableTintList = context.getColorStateList(R.color.tab_text)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }
            textSize = 14f
            isFocusable = true
            isClickable = true
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(14), 0, px(14), 0)
            setBackgroundResource(R.drawable.tab_bg)
            setTextColor(context.getColorStateList(R.color.tab_text))
            setOnClickListener {
                if (!keepOpen) dismiss()
                action()
            }
        }
        body.addView(b, LinearLayout.LayoutParams(-1, px(40)).apply { topMargin = px(2) })
        if (firstButton == null) firstButton = b
        return b
    }

    /** Opens under [anchor], right-aligned with it when [alignEnd]. */
    fun showUnder(anchor: View, alignEnd: Boolean = true) {
        prepare()
        card.measure(View.MeasureSpec.makeMeasureSpec(window.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val available = context.resources.displayMetrics.heightPixels - (loc[1] + anchor.height) - px(10) - px(24)
        window.height = if (card.measuredHeight > available) available else ViewGroup.LayoutParams.WRAP_CONTENT
        val x = if (alignEnd) anchor.width - window.width else 0
        window.showAsDropDown(anchor, x, px(10))
        focusFirst()
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
