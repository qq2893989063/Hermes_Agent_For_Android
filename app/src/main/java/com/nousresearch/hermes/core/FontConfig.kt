package com.nousresearch.hermes.core

import android.content.Context
import android.graphics.Typeface

enum class FontFamily { SYSTEM, SANS_BOLD, SERIF, MONO }
enum class FontScale(val multiplier: Float) { SMALL(0.85f), NORMAL(1.0f), LARGE(1.25f), XLARGE(1.5f) }

data class FontConfig(val family: FontFamily, val scale: FontScale) {
    fun typeface(): Typeface = when (family) {
        FontFamily.SYSTEM -> Typeface.SANS_SERIF
        FontFamily.SANS_BOLD -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        FontFamily.SERIF -> Typeface.SERIF
        FontFamily.MONO -> Typeface.MONOSPACE
    }

    companion object {
        private const val PREFS = "hermes_config"
        private const val FAMILY = "font_family"
        private const val SCALE = "font_scale"

        fun load(context: Context): FontConfig {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val family = runCatching { FontFamily.valueOf(p.getString(FAMILY, FontFamily.SYSTEM.name)!!) }
                .getOrDefault(FontFamily.SYSTEM)
            val scale = runCatching { FontScale.valueOf(p.getString(SCALE, FontScale.NORMAL.name)!!) }
                .getOrDefault(FontScale.NORMAL)
            return FontConfig(family, scale)
        }

        fun save(context: Context, family: FontFamily, scale: FontScale) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(FAMILY, family.name).putString(SCALE, scale.name).apply()
        }
    }
}
