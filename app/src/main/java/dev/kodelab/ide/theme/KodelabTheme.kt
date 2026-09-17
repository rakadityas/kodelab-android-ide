package dev.kodelab.ide.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

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
    /**
     * What to draw *on* [accent] — a label on an accent-filled chip, the icons in
     * the status bar. It cannot be a constant: the accent is yellow in Kodelab's
     * own themes and dark teal in plenty of imported ones, and white on yellow
     * is unreadable. Derived rather than stored so every palette — built-in,
     * imported, or folded out of a code scheme — gets it for free.
     *
     * 0.179 is where contrast against white and against black are equal, so
     * whichever side of it the accent falls on is the legible choice.
     */
    val onAccent: Color get() = if (accent.luminance() > 0.179f) Color(0xFF14191C) else Color.White

    /** The token map handed to the web editor so it matches the native chrome. */
    fun toWebTokens(): Map<String, String> = mapOf(
        "chrome" to chrome.hex(), "panel" to panel.hex(), "surface" to surface.hex(),
        "overlay" to overlay.hex(), "border" to border.hex(),
        "textPrimary" to textPrimary.hex(), "textMuted" to textMuted.hex(),
        "accent" to accent.hex(), "accentMuted" to accentMuted.hex(),
        "good" to good.hex(), "warn" to warn.hex(), "crit" to crit.hex(),
        "base" to if (isDark) "dark" else "light",
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

    /**
     * Yellow on paper. The accent is a deep gold rather than the icon's bright
     * yellow for one reason: it has to work as *text* and as small icons on a
     * near-white surface, and bright yellow on white is invisible. This reads as
     * the same colour family at 5.4:1 against the page.
     *
     * The greys are warmed to match — same lightness as before, hue moved off
     * blue — so the accent looks chosen rather than dropped in.
     */
    val light = EditorPalette(
        chrome = Color(0xFFF0EEE7), panel = Color(0xFFF5F3EC), surface = Color(0xFFFDFDFB),
        overlay = Color(0xFFFFFFFF), border = Color(0xFFDEDACE),
        textPrimary = Color(0xFF1C1A14), textMuted = Color(0xFF6B6455),
        accent = Color(0xFF8F6200), accentMuted = Color(0xFFB98C33),
        tabActive = Color(0xFFFDFDFB), tabInactive = Color(0xFFE9E6DC),
        good = Color(0xFF2F7D4F), warn = Color(0xFFB5541A), crit = Color(0xFFB23B3B),
        isDark = false,
    )

    /**
     * AMOLED-first: the editor and terminal are true black, so on an OLED panel
     * those pixels are off. The chrome around them is lifted just enough to
     * read as structure rather than as a lighter shade of grey.
     *
     * Against black the accent can be the icon's yellow exactly — 13:1, and the
     * one saturated thing on the screen. [onAccent] turns the text on top of it
     * dark, which is the whole reason that property exists. `warn` moves to
     * orange so a warning still reads as a warning next to it.
     */
    val dark = EditorPalette(
        chrome = Color(0xFF0F0D0A), panel = Color(0xFF16130E), surface = Color(0xFF000000),
        overlay = Color(0xFF1C1812), border = Color(0xFF2C2619),
        textPrimary = Color(0xFFEFEAE0), textMuted = Color(0xFFADA595),
        accent = Color(0xFFFFC400), accentMuted = Color(0xFF7A6220),
        tabActive = Color(0xFF000000), tabInactive = Color(0xFF16130E),
        good = Color(0xFF5CC088), warn = Color(0xFFF08A3C), crit = Color(0xFFE07A7A),
        isDark = true,
    )

    /**
     * Resolve a theme id to a palette. Imported themes (from [custom], keyed by
     * their `import-…` id) win over the built-ins; unknown ids fall back to the
     * system-appropriate built-in so a deleted custom theme can't break the UI.
     */
    fun paletteFor(
        themeId: String,
        systemDark: Boolean,
        custom: Map<String, EditorPalette> = emptyMap(),
    ): EditorPalette = when (themeId) {
        LIGHT -> light
        DARK -> dark
        SYSTEM -> if (systemDark) dark else light
        // A bundled code scheme themes the chrome too, so that one choice
        // dresses the whole app rather than only the code area.
        else -> CodeSchemes.byId(themeId)?.let { with(CodeSchemes) { it.toPalette() } }
            ?: custom[themeId]
            ?: if (systemDark) dark else light
    }
}

val LocalEditorPalette = staticCompositionLocalOf { KodelabThemes.dark }

@Composable
fun KodelabTheme(
    themeId: String,
    custom: Map<String, EditorPalette> = emptyMap(),
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val palette = KodelabThemes.paletteFor(themeId, systemDark, custom)

    // The clock and battery belong to the system, and edge-to-edge draws them
    // over our chrome. Their light/dark appearance follows the *device* theme
    // unless we say otherwise, so a light Kodelab on a dark-mode phone got
    // white icons on a near-white bar. Track the palette we actually drew.
    val view = LocalView.current
    if (!view.isInEditMode) {
        val activity = view.context as? Activity
        SideEffect {
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !palette.isDark
                    isAppearanceLightNavigationBars = !palette.isDark
                }
            }
        }
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
