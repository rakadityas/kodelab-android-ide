package dev.kodelab.ide.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Kodelab's own theme system. Bundled themes are hand-tuned for small high-DPI
 * screens (higher base contrast, calmer chrome). We do not ship any other editor's
 * theme files verbatim; users can import standard theme JSON at runtime (see
 * workspace/.kodelab/themes/).
 */
data class EditorPalette(
    val chrome: Color,        // activity rail / title bars
    val panel: Color,         // side panel, tab bar
    val surface: Color,       // editor background
    val overlay: Color,       // command palette, menus
    val border: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val accent: Color,
    val accentMuted: Color,
    val tabActive: Color,
    val tabInactive: Color,
    val good: Color,
    val warn: Color,
    val crit: Color,
    val isDark: Boolean,
) {
    /** The token map handed to the web editor so Monaco/xterm match the native chrome. */
    fun toWebTokens(): Map<String, String> = mapOf(
        "chrome" to chrome.hex(), "panel" to panel.hex(), "surface" to surface.hex(),
        "overlay" to overlay.hex(), "border" to border.hex(),
        "textPrimary" to textPrimary.hex(), "textMuted" to textMuted.hex(),
        "accent" to accent.hex(), "accentMuted" to accentMuted.hex(),
        "good" to good.hex(), "warn" to warn.hex(), "crit" to crit.hex(),
        "base" to if (isDark) "vs-dark" else "vs",
    )
}

private fun Color.hex(): String {
    val a = (alpha * 255).toInt(); val r = (red * 255).toInt()
    val g = (green * 255).toInt(); val b = (blue * 255).toInt()
    return "#%02X%02X%02X%02X".format(r, g, b, a)
}

object KodelabThemes {
    const val LIGHT = "kodelab-light"
    const val DARK = "kodelab-dark"
    const val SYSTEM = "system"

    val light = EditorPalette(
        chrome = Color(0xFFE9EEF0), panel = Color(0xFFF0F4F5), surface = Color(0xFFFCFDFD),
        overlay = Color(0xFFFFFFFF), border = Color(0xFFD5DDE0),
        textPrimary = Color(0xFF14191C), textMuted = Color(0xFF5C6B70),
        accent = Color(0xFF0C7D8C), accentMuted = Color(0xFF7FB0B7),
        tabActive = Color(0xFFFCFDFD), tabInactive = Color(0xFFE3E9EB),
        good = Color(0xFF2F7D4F), warn = Color(0xFFB26A12), crit = Color(0xFFB23B3B),
        isDark = false,
    )

    val dark = EditorPalette(
        chrome = Color(0xFF12181B), panel = Color(0xFF171F24), surface = Color(0xFF11171A),
        overlay = Color(0xFF1E282D), border = Color(0xFF2B373D),
        textPrimary = Color(0xFFE7EDEE), textMuted = Color(0xFF9AACB2),
        accent = Color(0xFF3FB6C4), accentMuted = Color(0xFF2A6870),
        tabActive = Color(0xFF11171A), tabInactive = Color(0xFF1A2226),
        good = Color(0xFF5CC088), warn = Color(0xFFE0A24E), crit = Color(0xFFE07A7A),
        isDark = true,
    )
}

val LocalEditorPalette = staticCompositionLocalOf { KodelabThemes.dark }

@Composable
fun KodelabTheme(themeId: String, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val palette = when (themeId) {
        KodelabThemes.LIGHT -> KodelabThemes.light
        KodelabThemes.DARK -> KodelabThemes.dark
        else -> if (systemDark) KodelabThemes.dark else KodelabThemes.light
    }
    val scheme = if (palette.isDark) {
        darkColorScheme(
            primary = palette.accent, background = palette.surface, surface = palette.panel,
            onBackground = palette.textPrimary, onSurface = palette.textPrimary,
            outline = palette.border,
        )
    } else {
        lightColorScheme(
            primary = palette.accent, background = palette.surface, surface = palette.panel,
            onBackground = palette.textPrimary, onSurface = palette.textPrimary,
            outline = palette.border,
        )
    }
    CompositionLocalProvider(LocalEditorPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}
