package ir.jaru.app

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object Lang {
    private const val PREF = "once"
    private const val KEY_UI = "ui_lang"
    private const val KEY_INTRO = "seen_intro"

    fun ui(ctx: Context): String {
        val sp = prefs(ctx)
        if (!sp.contains(KEY_UI)) {
            sp.edit().putString(KEY_UI, "en").apply()
            return "en"
        }
        return sp.getString(KEY_UI, "en") ?: "en"
    }

    fun isFa(ctx: Context) = ui(ctx) == "fa"

    fun toggle(ctx: Context) {
        prefs(ctx).edit().putString(KEY_UI, if (isFa(ctx)) "en" else "fa").apply()
    }

    fun seenIntro(ctx: Context) = prefs(ctx).getBoolean(KEY_INTRO, false)

    fun markIntro(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_INTRO, true).apply()
    }

    fun wrap(base: Context): Context {
        val locale = Locale(ui(base))
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        cfg.setLayoutDirection(locale)
        return base.createConfigurationContext(cfg)
    }

    private fun prefs(ctx: Context) =
        (ctx.applicationContext ?: ctx).getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
