package com.nello.tvhome

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A row of pill buttons (the tabs, the top-right icons) with one sliding highlight:
 * a white pill glides to the focused item and its label/icon turns dark as the pill passes under it.
 * When nothing in the row is focused, a faint pill marks the selected item (the current tab).
 *
 * It's also entered on one specific child: when focus comes from below, only [entry] is offered
 * to the focus search, so the highlight goes straight there.
 */
class FocusGroup @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    var entry: (() -> View?)? = null

    /** while a hand-off to/from the other bar is playing, normal retargeting waits */
    private var handoff = false

    /**
     * The focus left for the other bar ([dir] +1 = to the right): the pill slides that way and
     * disappears into this bar's edge, stretching like a liquid.
     */
    fun slideOut(dir: Int) {
        if (pill.width() <= 0f || visibleAmount <= 0.01f) return
        handoff = true
        animator.cancel()
        // squashes into a small drop against the edge it's heading to, and dissolves there
        val edge = if (dir > 0) (buttons().lastOrNull()?.right ?: width).toFloat() else (buttons().firstOrNull()?.left ?: 0).toFloat()
        val drop = 14 * dp
        fromL = pill.left; fromR = pill.right
        fromStrength = strength; fromVisible = visibleAmount
        toL = if (dir > 0) edge - drop else edge; toR = toL + drop
        toStrength = strength; toVisible = 0f
        animator.start()
    }

    /** The focus arrived from the other bar: the pill comes in from this bar's edge onto [target]. */
    fun slideIn(dir: Int, target: View, delayMs: Long) {
        handoff = true
        animator.cancel()
        // born as a small drop at the edge it comes from, then stretches onto the item
        val drop = 14 * dp
        val edge = if (dir > 0) (buttons().firstOrNull()?.left ?: 0).toFloat() else (buttons().lastOrNull()?.right ?: width).toFloat()
        pill.left = if (dir > 0) edge else edge - drop; pill.right = pill.left + drop
        visibleAmount = 0f
        strength = 1f
        invalidate()
        postDelayed({
            fromL = pill.left; fromR = pill.right
            fromStrength = 1f; fromVisible = 0f
            toL = target.left.toFloat(); toR = target.right.toFloat()
            toStrength = 1f; toVisible = 1f
            lastTarget = target
            animator.start()
        }, delayMs)
    }

    /** false: no faint pill on the selected item when the row isn't focused (text tabs) */
    var showSelected = true

    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pill = RectF()
    private var fromL = 0f
    private var fromR = 0f
    private var toL = 0f
    private var toR = 0f
    /** 0 = faint "selected" pill, 1 = white "focused" pill */
    private var strength = 0f
    private var fromStrength = 0f
    private var toStrength = 0f
    private var visibleAmount = 0f
    private var fromVisible = 0f
    private var toVisible = 0f
    private var initialized = false

    /*
     * "Liquid" pill: the leading edge leaves first and fast, the trailing edge follows a beat
     * later and lands with a tiny overshoot — the pill stretches between the two items and
     * snaps back on the new one, like a drop.
     */
    private val lead = android.view.animation.PathInterpolator(0.25f, 0.9f, 0.3f, 1f)
    private val trail = android.view.animation.PathInterpolator(0.5f, 0f, 0.25f, 1.12f)

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 680 // ≈340 ms with the system animation scale at 0.5x
        interpolator = android.view.animation.LinearInterpolator()
        addListener(object : android.animation.AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationStart(a: android.animation.Animator) { cancelled = false }
            override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
            override fun onAnimationEnd(a: android.animation.Animator) {
                // settled: the item under the pill is drawn natively dark (crisp, real colours)
                if (!cancelled) { updateInk(); invalidate() }
                if (cancelled || !handoff) return
                handoff = false
                // settle on whatever is focused now (in case the focus moved during the hand-off)
                retarget(animate = true)
            }
        })
        addUpdateListener {
            val t = it.animatedValue as Float
            val movingRight = toL > fromL
            val tl = lead.getInterpolation(t)
            val tt = trail.getInterpolation(t)
            pill.left = fromL + (toL - fromL) * (if (movingRight) tt else tl)
            pill.right = fromR + (toR - fromR) * (if (movingRight) tl else tt)
            if (pill.right - pill.left < 8 * dp) {
                val c = (pill.left + pill.right) / 2
                pill.left = c - 4 * dp; pill.right = c + 4 * dp
            }
            strength = fromStrength + (toStrength - fromStrength) * tl
            visibleAmount = fromVisible + (toVisible - fromVisible) * tl
            updateInk()
            invalidate()
        }
    }

    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, _ -> retarget(animate = true) }

    init {
        setWillNotDraw(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
        super.onDetachedFromWindow()
    }

    private var lastTarget: View? = null

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val target = currentTarget()
        if (initialized && target != null && target === lastTarget) {
            // same item, only resized/moved (e.g. the Cerca tab widening): the pill hugs its edges
            if (animator.isRunning) {
                toL = target.left.toFloat(); toR = target.right.toFloat()
            } else {
                pill.left = target.left.toFloat(); pill.right = target.right.toFloat()
                toL = pill.left; toR = pill.right
                fromL = toL; fromR = toR
                updateInk()
                invalidate()
            }
            return
        }
        retarget(animate = initialized)
        initialized = true
    }

    private fun currentTarget(): View? {
        val items = buttons()
        return items.firstOrNull { it.hasFocus() || it.isFocused } ?: if (showSelected) items.firstOrNull { it.isSelected } else null
    }

    /** Call after changing which child is "selected" (the current tab). */
    fun refresh() {
        retarget(animate = true)
        // labels follow the selection even when the highlight didn't move
        updateInk()
        invalidate()
    }

    private fun buttons() = (0 until childCount).map { getChildAt(it) }.filter { it.visibility == VISIBLE && it.isFocusable }

    private fun retarget(animate: Boolean) {
        if (handoff) return
        val items = buttons()
        val focused = items.firstOrNull { it.hasFocus() || it.isFocused }
        val target = focused ?: if (showSelected) items.firstOrNull { it.isSelected } else null
        lastTarget = target
        val newStrength = if (focused != null) 1f else 0f
        val newVisible = if (target != null) 1f else 0f
        val l = target?.left?.toFloat() ?: pill.left
        val r = target?.right?.toFloat() ?: pill.right
        // nothing changes in this bar (e.g. moving between cards below): don't redraw it at all
        if (initialized && l == toL && r == toR && newStrength == toStrength && newVisible == toVisible) return
        animator.cancel()
        fromL = pill.left; fromR = pill.right
        fromStrength = strength; fromVisible = visibleAmount
        toL = l; toR = r; toStrength = newStrength; toVisible = newVisible
        if (!animate || pill.width() <= 0f) {
            // first time: appear in place instead of sliding in from the corner
            pill.left = l; pill.right = r
            fromL = l; fromR = r
        }
        if (animate) animator.start() else {
            strength = newStrength; visibleAmount = newVisible
            updateInk()
            invalidate()
        }
    }

    /**
     * When the pill stands still on an item, that item is drawn natively in the "on pill"
     * colour (text recoloured, icons in their focused version): crisp edges and real colours.
     * Only while the pill slides is the dark ink a mask (see dispatchDraw).
     */
    private var directTarget: View? = null
    private val savedColors = java.util.WeakHashMap<android.widget.TextView, Int>()

    /** icon bars: each icon goes dark natively as the pill covers it (no mask, colours kept) */
    private fun iconInk() {
        val k = strength * visibleAmount
        for (b in buttons()) {
            val icons = if (b is ViewGroup) (0 until b.childCount).map { b.getChildAt(it) }.filterIsInstance<StatusIcon>() else emptyList()
            if (icons.isEmpty()) continue
            val overlap = (minOf(pill.right, b.right.toFloat()) - maxOf(pill.left, b.left.toFloat())).coerceAtLeast(0f)
            val cover = (overlap / b.width.coerceAtLeast(1)).coerceIn(0f, 1f)
            icons.forEach { it.ink = cover * k }
        }
    }

    private val hasLabels get() = buttons().any { it is android.widget.TextView }

    private fun updateInk() {
        if (!hasLabels) { iconInk(); return }
        val settled = !animator.isRunning && strength * visibleAmount > 0.99f
        val target = if (settled) lastTarget else null
        if (target === directTarget) return
        directTarget?.let { inkDirect(it, false) }
        directTarget = target
        target?.let { inkDirect(it, true) }
    }

    /** A label's normal colour: applied now, or after the pill leaves it (if it's under the pill). */
    fun setLabelColor(t: android.widget.TextView, color: Int) {
        if (directTarget === t) savedColors[t] = color else t.setTextColor(color)
    }

    private fun inkDirect(v: View, on: Boolean) {
        val views = if (v is ViewGroup) (0 until v.childCount).map { v.getChildAt(it) } + v else listOf(v)
        views.forEach { c ->
            when (c) {
                is StatusIcon -> c.ink = if (on) 1f else 0f
                is android.widget.TextView -> if (on) {
                    savedColors[c] = c.currentTextColor
                    c.setTextColor(Theme.onAccent)
                } else savedColors.remove(c)?.let { c.setTextColor(it) }
            }
        }
    }

    // dark ink at full strength even for dimmed (60% white) labels: colour → #111, alpha × 2.5
    private val inkPaint = Paint()
    private var inkFor = 0

    /** Labels under the pill take the "on accent" colour (dark on light accents, white on dark). */
    private fun inkFilter() {
        val c = Theme.onAccent
        if (c == inkFor) return
        inkFor = c
        inkPaint.colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(
            0f, 0f, 0f, 0f, android.graphics.Color.red(c).toFloat(),
            0f, 0f, 0f, 0f, android.graphics.Color.green(c).toFloat(),
            0f, 0f, 0f, 0f, android.graphics.Color.blue(c).toFloat(),
            0f, 0f, 0f, 1.43f, 0f,
        ))
    }
    private val clip = android.graphics.Path()

    override fun dispatchDraw(canvas: Canvas) {
        val r = 20 * dp * Theme.roundness
        val showPill = visibleAmount > 0.01f && pill.width() > 0f
        // appearing/disappearing: grows from (shrinks to) the item's centre, not just a fade
        val grow = 0.55f + 0.45f * visibleAmount
        val cx = (pill.left + pill.right) / 2
        val half = pill.width() / 2 * grow
        val vInset = height * (1 - grow) / 2
        val dl = cx - half
        val dr = cx + half
        if (showPill) {
            // faint white when only "selected", solid white when focused
            val alpha = (0x2E + (0xF2 - 0x2E) * strength) * visibleAmount
            paint.color = (alpha.toInt().coerceIn(0, 255) shl 24) or (Theme.accent and 0xFFFFFF)
            canvas.drawRoundRect(dl, vInset, dr, height - vInset, r, r, paint)
        }
        super.dispatchDraw(canvas)
        // the labels again, dark, but only inside the white pill: as it slides, each letter turns
        // dark exactly where the pill covers it
        val ink = strength * visibleAmount
        if (showPill && ink > 0.01f && directTarget == null && hasLabels) {
            inkFilter()
            inkPaint.alpha = (ink * 255).toInt()
            clip.reset()
            clip.addRoundRect(dl, vInset, dr, height - vInset, r, r, android.graphics.Path.Direction.CW)
            val save = canvas.save()
            canvas.clipPath(clip)
            canvas.saveLayer(dl, vInset, dr, height - vInset, inkPaint)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(save)
        }
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * t).toInt()
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        val from = rootView.findFocus()
        val target = entry?.invoke()
        val vertical = direction == FOCUS_UP || direction == FOCUS_DOWN
        if (vertical && from != null && target != null && !isInside(from)) {
            target.addFocusables(views, direction, focusableMode)
            return
        }
        super.addFocusables(views, direction, focusableMode)
    }

    private fun isInside(v: View): Boolean {
        var p = v.parent
        while (p != null) {
            if (p === this) return true
            p = p.parent
        }
        return false
    }
}
