package dev.kodelab.ide.theme

import androidx.compose.ui.graphics.Color

/**
 * Colour schemes for the code area alone — the palette the editor paints with,
 * separate from the chrome around it (see [EditorPalette]).
 *
 * A scheme is more than a background: without token colours, switching "theme"
 * only changes the paper the same syntax highlighting is printed on. So each
 * one carries seven token roles, and the web side spreads each role across the
 * syntax tags that read as the same thing.
 *
 * The published schemes below are re-expressed in this type from their own
 * projects' documented colour values, each MIT-licensed and credited in NOTICE.
 * No theme file from another editor is bundled — importing those at runtime is
 * what `.kodelab/themes/` is for.
 */
data class CodeScheme(
    val id: String,
    val name: String,
    val isDark: Boolean,
    val background: Color,
    val foreground: Color,
    val lineNumbers: Color,
    val cursor: Color,
    val selection: Color,
    val currentLine: Color,
    // token roles
    val comment: Color,
    val keyword: Color,
    val string: Color,
    val number: Color,
    val type: Color,
    val function: Color,
    val operator: Color,
) {
    /**
     * The scheme as the web editor wants it: flat, every colour a #RRGGBB
     * string, and the token roles left as roles. Which syntax tag each role
     * paints is CodeMirror's business, so that mapping lives in
     * web/src/editor-core.mjs rather than here.
     */
    fun toWebTheme(): Map<String, Any> = mapOf(
        "dark" to isDark,
        "background" to background.hexRgb(),
        "foreground" to foreground.hexRgb(),
        "lineNumbers" to lineNumbers.hexRgb(),
        "cursor" to cursor.hexRgb(),
        "selection" to selection.hexRgb(),
        "currentLine" to currentLine.hexRgb(),
        "comment" to comment.hexRgb(),
        "keyword" to keyword.hexRgb(),
        "string" to string.hexRgb(),
        "number" to number.hexRgb(),
        "type" to type.hexRgb(),
        "function" to function.hexRgb(),
        "operator" to operator.hexRgb(),
    )
}

private fun Color.hexRgb(): String =
    "#%02X%02X%02X".format((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())

object CodeSchemes {

    /** Follow whatever the app theme is doing — the default. */
    const val AUTO = "auto"

    /**
     * Derive a scheme from a chrome palette, for "auto" and for any imported
     * theme: the editor's own colours come from the palette, and the syntax
     * roles are spread across its accents so the result is still readable
     * rather than monochrome.
     */
    fun fromPalette(palette: EditorPalette): CodeScheme = CodeScheme(
        id = AUTO,
        name = "Follow the app theme",
        isDark = palette.isDark,
        background = palette.surface,
        foreground = palette.textPrimary,
        lineNumbers = palette.textMuted,
        cursor = palette.accent,
        selection = palette.accentMuted,
        currentLine = palette.panel,
        comment = palette.textMuted,
        keyword = palette.accent,
        string = palette.good,
        number = palette.warn,
        type = palette.accentMuted,
        function = palette.accent,
        operator = palette.textMuted,
    )

    /**
     * The code area for Kodelab Dark and Kodelab Light.
     *
     * These are authored rather than derived. [fromPalette] spreads a *chrome*
     * palette over the syntax roles, and a chrome palette does not have enough
     * of them: `type` came out of `accentMuted`, which is a colour picked to sit
     * quietly behind other things, and `keyword`/`function` and
     * `comment`/`operator` collapsed onto one value each. The result reads as
     * one washed-out hue with occasional highlights.
     *
     * So each role gets its own hue here, and every one of them is chosen to
     * clear 4.5:1 against the editor background — see the contrast table in the
     * commit that introduced them. Gold stays on `keyword` because it is the
     * most frequent coloured token on screen and it is Kodelab's colour.
     */
    val kodelabDark = CodeScheme(
        id = "kodelab-dark-code", name = "Kodelab Dark", isDark = true,
        background = Color(0xFF000000), foreground = Color(0xFFEFEAE0),
        lineNumbers = Color(0xFF7C7568), cursor = Color(0xFFFFC400),
        selection = Color(0xFF3D3418), currentLine = Color(0xFF16130E),
        comment = Color(0xFF8A8578), keyword = Color(0xFFFFC400),
        string = Color(0xFF7EE081), number = Color(0xFFFF9E64),
        type = Color(0xFF6FD6E8), function = Color(0xFF8AB4FF),
        operator = Color(0xFFFF7BAC),
    )

    val kodelabLight = CodeScheme(
        id = "kodelab-light-code", name = "Kodelab Light", isDark = false,
        background = Color(0xFFFDFDFB), foreground = Color(0xFF1C1A14),
        lineNumbers = Color(0xFF9A9280), cursor = Color(0xFF8F6200),
        selection = Color(0xFFF6E3A6), currentLine = Color(0xFFF5F3EC),
        comment = Color(0xFF7A7261), keyword = Color(0xFF8F6200),
        string = Color(0xFF1F7A46), number = Color(0xFFB03A00),
        type = Color(0xFF7B3FA8), function = Color(0xFF0B5FA5),
        operator = Color(0xFFAD2B6B),
    )

    /**
     * Solarized — Ethan Schoonover, MIT. Its sixteen values are chosen for
     * equal perceived contrast, which is why the two variants share every
     * accent and differ only in which end of the base range is paper and which
     * is ink.
     */
    val solarizedDark = CodeScheme(
        id = "solarized-dark", name = "Solarized Dark", isDark = true,
        background = Color(0xFF002B36), foreground = Color(0xFF839496),
        lineNumbers = Color(0xFF586E75), cursor = Color(0xFF93A1A1),
        selection = Color(0xFF073642), currentLine = Color(0xFF073642),
        comment = Color(0xFF586E75), keyword = Color(0xFF859900),
        string = Color(0xFF2AA198), number = Color(0xFFD33682),
        type = Color(0xFFB58900), function = Color(0xFF268BD2),
        operator = Color(0xFF93A1A1),
    )

    val solarizedLight = CodeScheme(
        id = "solarized-light", name = "Solarized Light", isDark = false,
        background = Color(0xFFFDF6E3), foreground = Color(0xFF657B83),
        lineNumbers = Color(0xFF93A1A1), cursor = Color(0xFF586E75),
        selection = Color(0xFFEEE8D5), currentLine = Color(0xFFEEE8D5),
        comment = Color(0xFF93A1A1), keyword = Color(0xFF859900),
        string = Color(0xFF2AA198), number = Color(0xFFD33682),
        type = Color(0xFFB58900), function = Color(0xFF268BD2),
        operator = Color(0xFF586E75),
    )

    /** Nord — Sven Greb, MIT. Low-saturation arctic blues. */
    val nord = CodeScheme(
        id = "nord", name = "Nord", isDark = true,
        background = Color(0xFF2E3440), foreground = Color(0xFFD8DEE9),
        lineNumbers = Color(0xFF4C566A), cursor = Color(0xFF88C0D0),
        selection = Color(0xFF434C5E), currentLine = Color(0xFF3B4252),
        comment = Color(0xFF616E88), keyword = Color(0xFF81A1C1),
        string = Color(0xFFA3BE8C), number = Color(0xFFB48EAD),
        type = Color(0xFF8FBCBB), function = Color(0xFF88C0D0),
        operator = Color(0xFF81A1C1),
    )

    /** Dracula — Zeno Rocha, MIT. */
    val dracula = CodeScheme(
        id = "dracula", name = "Dracula", isDark = true,
        background = Color(0xFF282A36), foreground = Color(0xFFF8F8F2),
        lineNumbers = Color(0xFF6272A4), cursor = Color(0xFFF8F8F0),
        selection = Color(0xFF44475A), currentLine = Color(0xFF44475A),
        comment = Color(0xFF6272A4), keyword = Color(0xFFFF79C6),
        string = Color(0xFFF1FA8C), number = Color(0xFFBD93F9),
        type = Color(0xFF8BE9FD), function = Color(0xFF50FA7B),
        operator = Color(0xFFFF79C6),
    )

    /** Gruvbox — morhetz, MIT. Warm, retro-groove contrast. */
    val gruvboxDark = CodeScheme(
        id = "gruvbox-dark", name = "Gruvbox Dark", isDark = true,
        background = Color(0xFF282828), foreground = Color(0xFFEBDBB2),
        lineNumbers = Color(0xFF7C6F64), cursor = Color(0xFFEBDBB2),
        selection = Color(0xFF3C3836), currentLine = Color(0xFF32302F),
        comment = Color(0xFF928374), keyword = Color(0xFFFB4934),
        string = Color(0xFFB8BB26), number = Color(0xFFD3869B),
        type = Color(0xFFFABD2F), function = Color(0xFF8EC07C),
        operator = Color(0xFFFE8019),
    )

    /** One Dark — Atom, MIT. */
    val oneDark = CodeScheme(
        id = "one-dark", name = "One Dark", isDark = true,
        background = Color(0xFF282C34), foreground = Color(0xFFABB2BF),
        lineNumbers = Color(0xFF4B5263), cursor = Color(0xFF528BFF),
        selection = Color(0xFF3E4451), currentLine = Color(0xFF2C313C),
        comment = Color(0xFF5C6370), keyword = Color(0xFFC678DD),
        string = Color(0xFF98C379), number = Color(0xFFD19A66),
        type = Color(0xFFE5C07B), function = Color(0xFF61AFEF),
        operator = Color(0xFF56B6C2),
    )

    /**
     * A high-contrast light scheme for reading outdoors — Kodelab's own, and
     * the counterweight to a list that is otherwise mostly dark.
     */
    val paperLight = CodeScheme(
        id = "paper-light", name = "Paper", isDark = false,
        background = Color(0xFFFFFFFF), foreground = Color(0xFF1B1F23),
        lineNumbers = Color(0xFF8A9199), cursor = Color(0xFF0B5FA5),
        selection = Color(0xFFD7E6F5), currentLine = Color(0xFFF2F5F8),
        comment = Color(0xFF6A737D), keyword = Color(0xFFAF00A7),
        string = Color(0xFF0A6B33), number = Color(0xFF9A4B00),
        type = Color(0xFF7A4CC0), function = Color(0xFF0B5FA5),
        operator = Color(0xFF44515C),
    )

    /**
     * Dress the whole app from a code scheme, so picking one theme themes
     * everything rather than leaving the chrome on a different palette.
     *
     * The scheme only describes the code area, so the chrome around it is
     * derived: surfaces step away from the editor background by a few percent
     * (down for a dark scheme, up for a light one) to read as structure, and
     * the semantic colours borrow the token roles closest in meaning.
     */
    fun CodeScheme.toPalette(): EditorPalette = EditorPalette(
        chrome = background.shade(if (isDark) -0.35f else -0.06f),
        panel = background.shade(if (isDark) -0.18f else -0.03f),
        surface = background,
        overlay = background.shade(if (isDark) 0.08f else 0.02f),
        border = background.mix(foreground, 0.22f),
        textPrimary = foreground,
        textMuted = comment,
        accent = function,
        accentMuted = selection,
        tabActive = background,
        tabInactive = background.shade(if (isDark) -0.18f else -0.03f),
        good = string,
        warn = type,
        crit = keyword,
        isDark = isDark,
    )

    /** Everything offered in the editor colours picker, in listed order. */
    val all = listOf(
        solarizedDark, solarizedLight, nord, dracula, gruvboxDark, oneDark, paperLight,
    )

    fun byId(id: String): CodeScheme? = all.firstOrNull { it.id == id }
}

/** Move a colour toward black (negative) or white (positive) by [amount]. */
private fun Color.shade(amount: Float): Color =
    mix(if (amount < 0) Color.Black else Color.White, kotlin.math.abs(amount))

/** Blend toward [other] by [t] (0 = unchanged, 1 = fully [other]). */
private fun Color.mix(other: Color, t: Float): Color = Color(
    red = red + (other.red - red) * t,
    green = green + (other.green - green) * t,
    blue = blue + (other.blue - blue) * t,
    alpha = 1f,
)
