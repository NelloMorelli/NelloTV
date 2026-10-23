package com.nello.tvhome

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics

/**
 * One size for the whole interface (Impostazioni → Aspetto → Dimensione interfaccia): every
 * screen runs at the device density times this factor, so sizes, spacing and text all scale
 * together and keep their proportions.
 */
object UiScale {
    val OPTIONS = listOf(1f to "100%", 0.9f to "90%", 0.8f to "80%", 0.7f to "70%")
    const val DEFAULT = 0.8f

    fun factor(c: Context) = c.getSharedPreferences("theme", Context.MODE_PRIVATE).getFloat("uiScale", DEFAULT)

    fun wrap(base: Context): Context {
        val f = factor(base)
        if (f == 1f) return base
        val cfg = Configuration(base.resources.configuration)
        cfg.densityDpi = (DisplayMetrics.DENSITY_DEVICE_STABLE * f).toInt()
        return base.createConfigurationContext(cfg)
    }
}
