package com.nello.tvhome

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Full-page screens ("Dettagli", settings) in the launcher's style:
 * the wallpaper dimmed behind a tall glass panel with a list of rows.
 */
abstract class GlassPage : ComponentActivity() {

    protected lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(32), dp(48), dp(48))
        }
        val scroll = ScrollView(this).apply {
            addView(list)
            isFillViewport = true
        }
        val panel = GlassView(this).apply {
            zoomOnFocus = false
            addView(scroll)
        }
        val root = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.wallpaper)
            addView(View(context).apply { setBackgroundColor(0xB3000000.toInt()) })
            addView(panel, FrameLayout.LayoutParams(dp(600), -1).apply {
                setMargins(dp(24), dp(24), 0, dp(24))
            })
        }
        setContentView(root)
    }

    protected fun title(text: String, subtitle: String? = null) {
        list.addView(TextView(this).apply {
            this.text = text
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        if (subtitle != null) list.addView(TextView(this).apply {
            this.text = subtitle
            textSize = 13f
            setTextColor(getColor(R.color.text_dim))
            setPadding(0, dp(4), 0, 0)
        })
        list.addView(View(this), LinearLayout.LayoutParams(-1, dp(8)))
    }

    protected fun header(text: String) = list.addView(TextView(this).apply {
        this.text = text.uppercase()
        textSize = 11f
        letterSpacing = 0.1f
        setTextColor(getColor(R.color.accent))
        setPadding(dp(4), dp(20), 0, dp(6))
    })

    /** A focusable row; returns it so a pop-up can open under it. */
    protected fun item(title: String, value: String?, onClick: (View) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setBackgroundResource(R.drawable.tab_bg)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { onClick(it) }
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 15f
            isDuplicateParentStateEnabled = true
            setTextColor(getColorStateList(R.color.tab_text))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (value != null) row.addView(TextView(this).apply {
            text = value
            textSize = 13f
            isDuplicateParentStateEnabled = true
            setTextColor(getColorStateList(R.color.tab_text))
        })
        list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
        return row
    }

    /** A read-only "label · value" line; returns the value view to update it later. */
    protected fun info(label: String, value: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(getColor(R.color.text_dim))
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
