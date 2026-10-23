package com.nello.tvhome

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * Horizontal row of cards. When focus enters the row vertically it lands on a predictable card:
 * - from another row: the card in the same column as the one you came from;
 * - from the top menu (tabs, icons): the first card on screen.
 * Without this Android picks the card nearest to the previous focus (the one under the "Film" tab),
 * or for a row still off screen whichever card it likes, so the selection jumped sideways.
 */
class RowList @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : RecyclerView(context, attrs) {

    private val loc = IntArray(2)

    /** card to land on when entering from outside (the carousel: its wide card), if set */
    var preferred: (() -> View?)? = null

    /** right on the last card goes back to the first (carousels) */
    var wrapAround = false

    /** Vertical focus search only sees the one card we want to land on. */
    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        val from = rootView.findFocus()
        if ((direction == FOCUS_UP || direction == FOCUS_DOWN) && from != null && !contains(from)) {
            val target = target(from)
            if (target != null) {
                target.addFocusables(views, direction, focusableMode)
                return
            }
        }
        super.addFocusables(views, direction, focusableMode)
    }

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        val from = rootView.findFocus()
        if (from != null && from !== this && !contains(from)) {
            target(from)?.let { return it.requestFocus() }
        }
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
    }

    /**
     * Left/right never leave the row: the next card is focused even if it isn't laid out yet
     * (fast presses), and at the first/last card the selection stays put instead of jumping
     * to a tab, a top-right button or another row.
     */
    override fun focusSearch(focused: View, direction: Int): View? {
        if (direction != FOCUS_LEFT && direction != FOCUS_RIGHT) return super.focusSearch(focused, direction)
        val item = findContainingItemView(focused) ?: return super.focusSearch(focused, direction)
        val pos = getChildAdapterPosition(item)
        val count = adapter?.itemCount ?: 0
        if (pos == NO_POSITION) return focused
        var next = pos + if (direction == FOCUS_RIGHT) 1 else -1
        if (wrapAround && direction == FOCUS_RIGHT && next == count && count > 1) next = 0
        if (next !in 0 until count) return focused
        layoutManager?.findViewByPosition(next)?.let { if (it.isFocusable) return it }
        // not on screen yet: bring it in and focus it as soon as it's laid out
        scrollToPosition(next)
        focusWhenLaidOut(next, 8)
        return focused
    }

    /** Focus the card at [pos] once the list has laid it out (retries a few frames). */
    private fun focusWhenLaidOut(pos: Int, tries: Int) {
        postOnAnimation {
            val v = layoutManager?.findViewByPosition(pos)
            if (v != null) v.requestFocus() else if (tries > 0) focusWhenLaidOut(pos, tries - 1)
        }
    }

    private fun target(from: View): View? {
        val cards = (0 until childCount).map { getChildAt(it) }.filter { it.isFocusable && it.visibility == VISIBLE }
        if (cards.isEmpty()) return null
        preferred?.invoke()?.let { p -> if (p in cards) return p }
        if (!insideARow(from)) {
            // first card that is fully on screen (a scrolled row can have one peeking from the left)
            return cards.filter { it.left >= paddingLeft - 4 }.minByOrNull { it.left } ?: cards.minByOrNull { it.left }
        }
        val x = centerX(from)
        return cards.minByOrNull { Math.abs(centerX(it) - x) }
    }

    private fun insideARow(v: View): Boolean {
        var p = v.parent
        while (p != null) {
            if (p is RowList) return true
            p = p.parent
        }
        return false
    }

    private fun contains(v: View): Boolean {
        var p = v.parent
        while (p != null) {
            if (p === this) return true
            p = p.parent
        }
        return false
    }

    private fun centerX(v: View): Int {
        v.getLocationOnScreen(loc)
        return loc[0] + v.width / 2
    }
}
