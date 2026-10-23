package com.nello.tvhome

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * Vertical page for Esplora. It never scrolls by itself to follow the focus:
 * MainActivity decides where the page stops (top, "Continua a guardare", a grid row).
 */
class PageScroll @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {

    init {
        isVerticalScrollBarEnabled = false
        isSmoothScrollingEnabled = true
        overScrollMode = OVER_SCROLL_NEVER
    }

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?): Int = 0

    private var anim: android.animation.ValueAnimator? = null
    private var animTarget = -1

    /**
     * Glide to [y] with a soft ease-out (fast start, gentle landing), instead of ScrollView's
     * short linear jump. Starting a new glide mid-way continues smoothly from where it is.
     */
    fun glideTo(y: Int) {
        val max = ((getChildAt(0)?.height ?: 0) - height).coerceAtLeast(0)
        val target = y.coerceIn(0, max)
        if (target == animTarget && anim?.isRunning == true) return
        if (target == scrollY) return
        anim?.cancel()
        animTarget = target
        val from = scrollY
        val distance = Math.abs(target - from)
        anim = android.animation.ValueAnimator.ofInt(from, target).apply {
            // ≈300–420 ms at the Fire TV's 0.5x animation scale, longer for longer distances
            duration = (600 + distance / 3).coerceAtMost(840).toLong()
            interpolator = android.view.animation.PathInterpolator(0.2f, 0.9f, 0.25f, 1f)
            addUpdateListener { scrollTo(0, it.animatedValue as Int) }
            start()
        }
    }
}
