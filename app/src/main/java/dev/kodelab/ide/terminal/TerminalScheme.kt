package dev.kodelab.ide.terminal

import androidx.compose.ui.graphics.Color
import dev.kodelab.ide.theme.EditorPalette

/**
 * A terminal colour scheme: the 16 ANSI slots plus the surface they sit on.
 *
 * These palettes are original to Kodelab — picked here by hue/lightness targets
 * rather than copied from any existing terminal theme, which keeps the IP rules
 * in docs/IP-SAFETY.md intact. Each is checked to keep normal-weight text
 * readable against its own background.
 */
data class TerminalScheme(
    val id: String,
    val name: String,
    val isDark: Boolean,
    val background: Color,
    val foreground: Color,
    /** black, red, green, yellow, blue, magenta, cyan, white, then the 8 bright. */
    val ansi: List<Color>,
) {
    /** The palette colour for an SGR index, or the RGB carried by a truecolour slot. */
    fun colorFor(slot: Int): Color = when {
        slot < 0 -> Color.Unspecified
        slot and TerminalEmulator.TRUECOLOR != 0 -> Color(slot and 0xFFFFFF or (0xFF shl 24))
        slot in 0..15 -> ansi[slot]
        slot in 16..231 -> {
            val n = slot - 16
            fun ch(v: Int) = if (v == 0) 0 else 55 + v * 40
            Color(ch((n / 36) % 6), ch((n / 6) % 6), ch(n % 6))
        }
        slot in 232..255 -> {
            val v = 8 + (slot - 232) * 10
            Color(v, v, v)
        }
        else -> Color.Unspecified
    }
}

object TerminalSchemes {

    const val AUTO = "auto"

    /** Cool, low-glare dark — the default, tuned against Kodelab Dark's surface. */
    val Midnight = TerminalScheme(
        id = "midnight",
        name = "Midnight",
        isDark = true,
        background = Color(0xFF11161C),
        foreground = Color(0xFFCFD8E0),
        ansi = listOf(
            Color(0xFF3B4048), Color(0xFFE0685F), Color(0xFF62B872), Color(0xFFD3A24E),
            Color(0xFF539BD8), Color(0xFFB07CDC), Color(0xFF3FB3C2), Color(0xFFC5CDD3),
            Color(0xFF5A626C), Color(0xFFF0857A), Color(0xFF84D193), Color(0xFFEFC479),
            Color(0xFF7FBCEC), Color(0xFFC99BEB), Color(0xFF66D3E0), Color(0xFFF2F6F8),
        ),
    )

    /** Warm dark: amber/rust bias, easier on the eyes at night. */
    val Ember = TerminalScheme(
        id = "ember",
        name = "Ember",
        isDark = true,
        background = Color(0xFF1A1512),
        foreground = Color(0xFFE4D7C7),
        ansi = listOf(
            Color(0xFF463C34), Color(0xFFE2664F), Color(0xFFA8B85C), Color(0xFFE0A03F),
            Color(0xFF6FA3B8), Color(0xFFC57FA8), Color(0xFF5FB5A5), Color(0xFFD8CBBB),
            Color(0xFF6A5B4E), Color(0xFFF5836B), Color(0xFFC3D276), Color(0xFFF7BE5C),
            Color(0xFF8FC0D4), Color(0xFFDD9BC2), Color(0xFF7FD1C1), Color(0xFFFBF3E8),
        ),
    )

    /** Green-on-dark, the classic phosphor look, kept legible rather than lurid. */
    val Phosphor = TerminalScheme(
        id = "phosphor",
        name = "Phosphor",
        isDark = true,
        background = Color(0xFF0C1410),
        foreground = Color(0xFF9FE6B0),
        ansi = listOf(
            Color(0xFF2A3A30), Color(0xFFE07A6A), Color(0xFF5FD97F), Color(0xFFC9D96A),
            Color(0xFF63B9C4), Color(0xFFA98FD0), Color(0xFF4FD4C0), Color(0xFFBFE6C8),
            Color(0xFF48604F), Color(0xFFF29685), Color(0xFF86F0A0), Color(0xFFE2F08A),
            Color(0xFF8AD6E0), Color(0xFFC6ADE8), Color(0xFF77EBD9), Color(0xFFE6FBEA),
        ),
    )

    /** Light scheme for a bright room; darkened hues so text holds up on white. */
    val Paper = TerminalScheme(
        id = "paper",
        name = "Paper",
        isDark = false,
        background = Color(0xFFFBFBF8),
        foreground = Color(0xFF2C3238),
        ansi = listOf(
            Color(0xFF3A4148), Color(0xFFBE3B36), Color(0xFF2F7D3C), Color(0xFF9A6A12),
            Color(0xFF2062A8), Color(0xFF7B3FA8), Color(0xFF11737E), Color(0xFF6E767D),
            Color(0xFF5A636B), Color(0xFFD0524C), Color(0xFF3D9A4C), Color(0xFFB5841F),
            Color(0xFF2E79C4), Color(0xFF9155BF), Color(0xFF1A8C97), Color(0xFF1B2024),
        ),
    )

    val all = listOf(Midnight, Ember, Phosphor, Paper)

    /**
     * Resolve a preset id. [AUTO] follows the editor theme — the terminal sits
     * on the editor's own surface so the panel reads as part of the IDE.
     */
    fun resolve(id: String, palette: EditorPalette): TerminalScheme = when (id) {
        AUTO -> follow(palette)
        else -> all.firstOrNull { it.id == id } ?: follow(palette)
    }

    /** The editor palette's own colours, wearing a terminal scheme's shape. */
    private fun follow(palette: EditorPalette): TerminalScheme {
        val base = if (palette.isDark) Midnight else Paper
        return base.copy(
            id = AUTO,
            name = "Follow theme",
            isDark = palette.isDark,
            background = palette.surface,
            foreground = palette.textPrimary,
        )
    }
}
