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
        val b = TextView(context).apply {
            this.text = text
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
    fun showSpotlight(screen: View, hero: View, heroWidthDp: Int, heroHeightDp: Int) {
        prepare()
        // posters are hardware bitmaps, so the screen is captured with PixelCopy rather than View.draw
        val activity = context as android.app.Activity
        val shot = android.graphics.Bitmap.createBitmap(480, 270, android.graphics.Bitmap.Config.ARGB_8888)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        android.view.PixelCopy.request(activity.window, shot, { result ->
            // blur off the main thread, so the opening animation isn't stalled
            Thread {
                val blur = if (result == android.view.PixelCopy.SUCCESS) GlassView.makeBlur(shot) else GlassView.blurred
                main.post { openSpotlight(screen, hero, heroWidthDp, heroHeightDp, blur) }
            }.start()
        }, main)
    }

    private fun openSpotlight(screen: View, hero: View, heroWidthDp: Int, heroHeightDp: Int, blur: android.graphics.Bitmap?) {
        // the app on top, its menu right underneath, both centred
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
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
            addView(row, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
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
        hero.alpha = 0f
        hero.scaleX = 0.82f
        hero.scaleY = 0.82f
        hero.translationY = px(24).toFloat()
        card.alpha = 0f
        card.translationY = px(36).toFloat()
        root.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                root.viewTreeObserver.removeOnPreDrawListener(this)
                root.postOnAnimation {
                    android.animation.ValueAnimator.ofInt(0, 255).apply {
                        duration = 480
                        interpolator = easeOut
                        addUpdateListener { bg.alpha = it.animatedValue as Int }
                        start()
                    }
                    hero.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                        .setDuration(700).setInterpolator(easeOut).start()
                    card.animate().alpha(1f).translationY(0f)
                        .setStartDelay(140).setDuration(700).setInterpolator(easeOut).start()
                }
                return true
            }
        })

        // exit: quick fade, slightly shrinking
        exitAnimation = { done ->
            val easeIn = android.view.animation.AccelerateInterpolator(1.5f)
            android.animation.ValueAnimator.ofInt(bg.alpha, 0).apply {
                duration = 300
                interpolator = easeIn
                addUpdateListener { bg.alpha = it.animatedValue as Int }
                start()
            }
            card.animate().alpha(0f).translationY(px(16).toFloat()).setStartDelay(0).setDuration(260).setInterpolator(easeIn).start()
            hero.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(300).setInterpolator(easeIn)
                .withEndAction { done() }.start()
        }
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
