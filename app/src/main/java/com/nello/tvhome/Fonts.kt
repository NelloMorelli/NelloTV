package com.nello.tvhome

import android.content.Context
import android.graphics.Typeface

/** Poppins in the weights the launcher uses; loaded once (NelloApp). */
object Fonts {
    lateinit var thin: Typeface; private set
    lateinit var extraLight: Typeface; private set
    lateinit var light: Typeface; private set
    lateinit var regular: Typeface; private set
    lateinit var medium: Typeface; private set
    lateinit var semiBold: Typeface; private set
    lateinit var bold: Typeface; private set

    fun init(c: Context) {
        fun f(id: Int) = c.resources.getFont(id)
        thin = f(R.font.poppins_thin)
        extraLight = f(R.font.poppins_extralight)
        light = f(R.font.poppins_light)
        regular = f(R.font.poppins_regular)
        medium = f(R.font.poppins_medium)
        semiBold = f(R.font.poppins_semibold)
        bold = f(R.font.poppins_bold)
    }
}
