package dev.kodelab.ide.theme

import androidx.compose.ui.graphics.Color
import org.json.JSONObject

/**
 * Imports a *standard editor color-theme JSON* (the widely-used, open
 * `{ name, type, colors: { "editor.background": "#..", ... } }` shape) into a
 * Kodelab [EditorPalette].
 *
 * IP note: this parses the file *format* only and derives Kodelab's own 14-token
 * palette from whichever colors the user's file provides. Kodelab ships no
 * third-party theme content — the user supplies their own `.json`. We never copy
 * a proprietary theme's bytes into the app.
 *
 * All real logic lives in pure ARGB-int helpers so it is unit-testable without
 * Compose or Android; [parse] only wraps the results into [Color] at the end.
 */
object VsThemeImport {

    data class Parsed(val id: String, val name: String, val palette: EditorPalette)

    /** @throws IllegalArgumentException if the JSON has no usable `colors` object. */
    fun parse(json: String, fallbackName: String = "Imported"): Parsed {
        val root = runCatching { JSONObject(json) }
            .getOrElse { throw IllegalArgumentException("Not valid JSON") }
        val colors = root.optJSONObject("colors")
            ?: throw IllegalArgumentException("No \"colors\" object in theme")

        val map = HashMap<String, Int>()
        for (key in colors.keys()) {
            parseArgb(colors.optString(key))?.let { map[key] = it }
        }
        if (map.isEmpty()) throw IllegalArgumentException("No readable colors in theme")

        val name = root.optString("name").ifBlank { fallbackName }
        val type = root.optString("type").ifBlank { null }

        fun pick(vararg keys: String): Int? = keys.firstNotNullOfOrNull { map[it] }

        val bg = pick("editor.background") ?: pick("editorGroupHeader.tabsBackground") ?: DEFAULT_BG
        val fg = pick("editor.foreground", "foreground") ?: DEFAULT_FG
        val dark = isDarkFrom(type, bg)

        // Blend toward the background for "muted" derivations, toward fg for chrome.
        val panel = pick("sideBar.background", "editorGroupHeader.tabsBackground") ?: shade(bg, dark, 0.03)
        val chrome = pick("activityBar.background", "titleBar.activeBackground") ?: shade(bg, dark, 0.05)
        val overlay = pick("editorWidget.background", "editorSuggestWidget.background", "quickInput.background") ?: shade(panel, dark, 0.04)
        val accent = pick("focusBorder", "button.background", "activityBarBadge.background", "textLink.foreground", "progressBar.background") ?: DEFAULT_ACCENT
        val border = pick("panel.border", "contrastBorder", "editorGroupHeader.tabsBorder") ?: withAlpha(blend(bg, fg, 0.15), 0xFF)
        val textMuted = pick("descriptionForeground", "tab.inactiveForeground", "editorLineNumber.foreground") ?: blend(fg, bg, 0.45)
        val accentMuted = pick("progressBar.background", "activityBar.inactiveForeground")?.let { blend(it, bg, 0.35) } ?: blend(accent, bg, 0.45)
        val tabActive = pick("tab.activeBackground") ?: bg
        val tabInactive = pick("tab.inactiveBackground") ?: panel
        val good = pick("gitDecoration.addedResourceForeground", "editorGutter.addedBackground", "testing.iconPassed", "charts.green") ?: if (dark) 0xFF5CC088.toInt() else 0xFF2F7D4F.toInt()
        val warn = pick("editorWarning.foreground", "list.warningForeground", "charts.yellow") ?: if (dark) 0xFFE0A24E.toInt() else 0xFFB26A12.toInt()
        val crit = pick("editorError.foreground", "errorForeground", "gitDecoration.deletedResourceForeground", "charts.red") ?: if (dark) 0xFFE07A7A.toInt() else 0xFFB23B3B.toInt()

        val palette = EditorPalette(
            chrome = c(chrome), panel = c(panel), surface = c(bg), overlay = c(overlay),
            border = c(border), textPrimary = c(fg), textMuted = c(textMuted),
            accent = c(accent), accentMuted = c(accentMuted),
            tabActive = c(tabActive), tabInactive = c(tabInactive),
            good = c(good), warn = c(warn), crit = c(crit), isDark = dark,
        )
        return Parsed(slug(name), name, palette)
    }

    // ---- pure helpers (no Compose / Android) ----

    /** Parse #RGB / #RGBA / #RRGGBB / #RRGGBBAA into 0xAARRGGBB, or null. */
    fun parseArgb(raw: String?): Int? {
        val s = raw?.trim()?.removePrefix("#") ?: return null
        if (s.isEmpty() || s.any { it.digitToIntOrNull(16) == null }) return null
        fun hx(from: Int, len: Int) = s.substring(from, from + len).let {
            if (len == 1) (it + it) else it
        }.toInt(16)
        return when (s.length) {
            3 -> pack(0xFF, hx(0, 1), hx(1, 1), hx(2, 1))
            4 -> pack(hx(3, 1), hx(0, 1), hx(1, 1), hx(2, 1))
            6 -> pack(0xFF, hx(0, 2).and(0xFF), hx(2, 2).and(0xFF), hx(4, 2).and(0xFF))
            8 -> pack(hx(6, 2).and(0xFF), hx(0, 2).and(0xFF), hx(2, 2).and(0xFF), hx(4, 2).and(0xFF))
            else -> null
        }
    }

    private fun pack(a: Int, r: Int, g: Int, b: Int): Int =
        (a and 0xFF shl 24) or (r and 0xFF shl 16) or (g and 0xFF shl 8) or (b and 0xFF)

    /** Perceptual 0..1 luminance of an ARGB int (alpha ignored). */
    fun relativeLuminance(argb: Int): Double {
        val r = (argb ushr 16 and 0xFF) / 255.0
        val g = (argb ushr 8 and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** Decide dark vs light: explicit `type` wins, else background luminance. */
    fun isDarkFrom(type: String?, bgArgb: Int?): Boolean = when (type?.lowercase()) {
        "dark", "hc", "hcdark", "hc-black" -> true
        "light", "hclight", "hc-light" -> false
        else -> bgArgb?.let { relativeLuminance(it) < 0.5 } ?: true
    }

    /** Linear blend of two ARGB ints by [t] in [0,1] (result alpha forced opaque). */
    fun blend(a: Int, b: Int, t: Double): Int {
        val u = t.coerceIn(0.0, 1.0)
        fun mix(shift: Int): Int {
            val ca = a ushr shift and 0xFF
            val cb = b ushr shift and 0xFF
            return (ca + (cb - ca) * u).toInt().coerceIn(0, 255)
        }
        return pack(0xFF, mix(16), mix(8), mix(0))
    }

    /** Lighten (light theme) or darken (dark theme) toward chrome by [amount]. */
    private fun shade(argb: Int, dark: Boolean, amount: Double): Int =
        blend(argb, if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), amount)

    private fun withAlpha(argb: Int, a: Int): Int = (argb and 0x00FFFFFF) or (a and 0xFF shl 24)

    private fun c(argb: Int): Color = Color(argb)

    /** Stable id from a theme name, e.g. "One Dark Pro" -> "import-one-dark-pro". */
    fun slug(name: String): String {
        val base = name.lowercase().map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("").trim('-').replace(Regex("-+"), "-")
        return "import-" + base.ifEmpty { "theme" }
    }

    private const val DEFAULT_BG = 0xFF11171A.toInt()
    private const val DEFAULT_FG = 0xFFE7EDEE.toInt()
    private const val DEFAULT_ACCENT = 0xFF3FB6C4.toInt()
}
