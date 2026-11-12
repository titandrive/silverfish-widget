package local.silverfishwidget

import android.content.Context
import android.content.res.Configuration

/**
 * How a widget looks: light or dark ([mode] "system", "light" or "dark") and how opaque its
 * background is (0 to 100); the pet and text are always drawn solid. And what tapping it opens
 * ([tap] "silverfish", "book" or "home").
 */
data class Style(val mode: String = "system", val opacity: Int = 100, val tap: String = "silverfish") {
    fun isDark(ctx: Context): Boolean = when (mode) {
        "light" -> false
        "dark" -> true
        else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    companion object {
        val MODES = listOf("system", "light", "dark")
        val TAPS = listOf("home", "book", "silverfish")
        private fun prefs(ctx: Context) = ctx.getSharedPreferences("style", Context.MODE_PRIVATE)

        /** A widget's style; a new widget starts with the one set last. */
        fun load(ctx: Context, id: Int): Style {
            val p = prefs(ctx)
            val key = if (p.contains("w$id.mode")) "w$id" else "last"
            return Style(
                p.getString("$key.mode", "system") ?: "system", p.getInt("$key.opacity", 100),
                p.getString("$key.tap", "silverfish") ?: "silverfish",
            )
        }

        fun save(ctx: Context, id: Int, style: Style) {
            prefs(ctx).edit()
                .putString("w$id.mode", style.mode).putInt("w$id.opacity", style.opacity).putString("w$id.tap", style.tap)
                .putString("last.mode", style.mode).putInt("last.opacity", style.opacity).putString("last.tap", style.tap)
                .apply()
        }

        fun remove(ctx: Context, id: Int) {
            prefs(ctx).edit().remove("w$id.mode").remove("w$id.opacity").remove("w$id.tap").apply()
        }
    }
}
