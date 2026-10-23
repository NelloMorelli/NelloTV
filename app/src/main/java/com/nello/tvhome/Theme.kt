package com.nello.tvhome

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable

/**
 * Everything the "Personalizza" page controls, in one place: read once at start (and again when
 * something changes — the home screen is then rebuilt), used by the views while they draw.
 */
object Theme {

    /** accent: selector pill, focus borders, progress bars */
    var accent = Color.WHITE
    /** glass: 0 = light, 1 = normal, 2 = strong */
    var glass = 1
    /** extra darkening of the wallpaper, 0..0.8 */
    var dim = 0f
    /** carousel card size multiplier */
    var cardScale = 1f
    var showDock = true
    var showCardInfo = true
    var roundness = 1f

    val ACCENTS = listOf(
        "Bianco" to Color.WHITE,
        "Oro" to 0xFFE9C46A.toInt(),
        "Azzurro Napoli" to 0xFF4FA3E0.toInt(),
        "Rosso" to 0xFFE5484D.toInt(),
        "Verde" to 0xFF46C37B.toInt(),
        "Viola" to 0xFF9B7BFF.toInt(),
        "Arancione" to 0xFFFF9F43.toInt(),
        "Rosa" to 0xFFFF6FAE.toInt(),
    )

    /** Text/icons drawn on top of the accent: dark on light accents, white on dark ones. */
    val onAccent: Int
        get() {
            val l = 0.299 * Color.red(accent) + 0.587 * Color.green(accent) + 0.114 * Color.blue(accent)
            return if (l > 150) 0xFF111111.toInt() else Color.WHITE
        }

    fun load(c: Context) {
        val p = c.getSharedPreferences("theme", Context.MODE_PRIVATE)
        accent = p.getInt("accent", Color.WHITE)
        glass = p.getInt("glass", 1)
        dim = p.getFloat("dim", 0f)
        cardScale = p.getFloat("cardScale", 1f)
        showDock = p.getBoolean("showDock", true)
        showCardInfo = p.getBoolean("showCardInfo", true)
        roundness = p.getFloat("roundness", 1f)
    }

    /** A string that changes whenever anything visible changes (to know when to rebuild). */
    fun signature(c: Context) = c.getSharedPreferences("theme", Context.MODE_PRIVATE).all.toSortedMap().toString()

    fun edit(c: Context) = c.getSharedPreferences("theme", Context.MODE_PRIVATE).edit()

    private fun dp(c: Context) = c.resources.displayMetrics.density

    /** White (accent) border on the focused card. */
    fun cardFocus(c: Context): Drawable {
        val r = 12 * dp(c) * roundness
        return StateListDrawable().apply {
            setEnterFadeDuration(360); setExitFadeDuration(360)
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                setStroke((3 * dp(c)).toInt(), accent); cornerRadius = r
            })
            addState(intArrayOf(), GradientDrawable().apply {
                setStroke(dp(c).toInt(), 0x2EFFFFFF); cornerRadius = r
            })
        }
    }

    fun progressFill(c: Context): Drawable = GradientDrawable().apply {
        setColor(accent); cornerRadius = 2 * dp(c)
    }

    /** Glass tint alphas by intensity. */
    fun glassTint(focused: Boolean) = when (glass) {
        0 -> if (focused) 0x40 else 0x14
        2 -> if (focused) 0x66 else 0x38
        else -> if (focused) 0x52 else 0x24
    }

    fun frostColors() = when (glass) {
        0 -> intArrayOf(0x2EFFFFFF, 0x14FFFFFF, 0x0AFFFFFF)
        2 -> intArrayOf(0x66FFFFFF, 0x38FFFFFF, 0x24FFFFFF)
        else -> intArrayOf(0x47FFFFFF, 0x24FFFFFF, 0x14FFFFFF)
    }
}
