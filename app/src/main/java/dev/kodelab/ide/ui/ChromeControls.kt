package dev.kodelab.ide.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kodelab.ide.theme.LocalEditorPalette

/**
 * Chrome scale, from the open folder's presets. Every touch target in the IDE
 * chrome multiplies its size by this, so a phone-sized screen can trade a little
 * content area for targets that a thumb actually hits.
 */
val LocalUiScale = staticCompositionLocalOf { 1f }

/** This [Dp] at the current chrome scale. */
@Composable
fun scaled(value: Dp): Dp = value * LocalUiScale.current

/**
 * The IDE's standard icon control: scales with [LocalUiScale] and names itself
 * on long-press, so an icon-only rail is still discoverable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChromeIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    baseSize: Dp = 28.dp,
    baseIconSize: Dp = 16.dp,
    tint: Color = LocalEditorPalette.current.textMuted,
    enabled: Boolean = true,
) {
    WithTooltip(label) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.size(scaled(baseSize)),
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(scaled(baseIconSize)))
        }
    }
}

/**
 * Long-press [content] to see [label]. Material's tooltip already knows the
 * touch gesture; this only fixes the styling to the editor palette.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WithTooltip(label: String, content: @Composable () -> Unit) {
    val palette = LocalEditorPalette.current
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip(containerColor = palette.overlay, contentColor = palette.textPrimary) {
                Text(label, fontSize = 12.sp)
            }
        },
        state = rememberTooltipState(),
    ) {
        Box { content() }
    }
}
