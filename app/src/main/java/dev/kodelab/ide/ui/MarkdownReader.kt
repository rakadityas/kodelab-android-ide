package dev.kodelab.ide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kodelab.ide.editor.Markdown
import dev.kodelab.ide.theme.EditorPalette
import dev.kodelab.ide.theme.LocalEditorPalette

/**
 * Reading view for a Markdown document (REQ 2). Renders natively — no WebView,
 * no HTML — so it is scrollable with the keyboard down and follows the theme.
 */
@Composable
fun MarkdownReader(
    source: String,
    baseFontSizeSp: Int,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalEditorPalette.current
    val blocks = remember(source) { Markdown.parse(source) }
    val base = baseFontSizeSp.coerceIn(8, 40)

    LazyColumn(
        modifier = modifier.background(palette.surface),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(blocks.size) { idx ->
            MarkdownBlock(blocks[idx], base, palette, onLinkClick)
        }
    }
}

@Composable
private fun MarkdownBlock(
    block: Markdown.Block,
    base: Int,
    palette: EditorPalette,
    onLinkClick: (String) -> Unit,
) {
    when (block) {
        is Markdown.Block.Heading -> {
            val scale = when (block.level) {
                1 -> 1.85f; 2 -> 1.5f; 3 -> 1.28f; 4 -> 1.13f; 5 -> 1.03f; else -> 0.95f
            }
            Column {
                ClickableSpans(
                    block.spans, palette, onLinkClick,
                    size = (base * scale).sp,
                    lineHeight = (base * scale * 1.35f).sp,
                    weight = FontWeight.SemiBold,
                )
                if (block.level <= 2) {
                    Spacer(Modifier.height(6.dp))
                    Spacer(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
                }
            }
        }

        is Markdown.Block.Paragraph ->
            ClickableSpans(
                block.spans, palette, onLinkClick, base.sp, (base * 1.65f).sp,
                // full justification: flush on both margins, so a ragged right
                // edge can't read as an unintended break
                align = TextAlign.Justify,
            )

        is Markdown.Block.Item ->
            Row(Modifier.padding(start = (block.indent.coerceAtMost(4) * 16).dp)) {
                Text(
                    block.marker,
                    color = palette.accent,
                    fontSize = base.sp,
                    lineHeight = (base * 1.65f).sp,
                    modifier = Modifier.width(24.dp),
                )
                ClickableSpans(block.spans, palette, onLinkClick, base.sp, (base * 1.65f).sp)
            }

        is Markdown.Block.Quote ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Spacer(Modifier.width(3.dp).fillMaxHeight().background(palette.accentMuted))
                Spacer(Modifier.width(10.dp))
                ClickableSpans(
                    block.spans, palette, onLinkClick, base.sp, (base * 1.65f).sp,
                    italic = true, color = palette.textMuted,
                )
            }

        is Markdown.Block.Code ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(palette.panel)
                    .border(1.dp, palette.border, RoundedCornerShape(6.dp))
                    .padding(10.dp),
            ) {
                block.language?.let {
                    Text(it, color = palette.textMuted, fontSize = (base - 3).coerceAtLeast(8).sp)
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    block.code,
                    color = palette.textPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = (base - 1).coerceAtLeast(8).sp,
                    lineHeight = ((base - 1) * 1.45f).sp,
                    softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }

        is Markdown.Block.Table ->
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                TableRow(block.header, base, palette, onLinkClick, header = true)
                block.rows.forEach { TableRow(it, base, palette, onLinkClick, header = false) }
            }

        Markdown.Block.Rule ->
            Spacer(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
    }
}

@Composable
private fun TableRow(
    cells: List<List<Markdown.Span>>,
    base: Int,
    palette: EditorPalette,
    onLinkClick: (String) -> Unit,
    header: Boolean,
) {
    Row(
        Modifier
            .background(if (header) palette.panel else palette.surface)
            .border(1.dp, palette.border),
    ) {
        cells.forEach { cell ->
            Box(Modifier.width(140.dp).padding(horizontal = 8.dp, vertical = 6.dp)) {
                ClickableSpans(
                    cell, palette, onLinkClick, base.sp, (base * 1.5f).sp,
                    weight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun ClickableSpans(
    spans: List<Markdown.Span>,
    palette: EditorPalette,
    onLinkClick: (String) -> Unit,
    size: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    weight: FontWeight = FontWeight.Normal,
    italic: Boolean = false,
    color: androidx.compose.ui.graphics.Color = palette.textPrimary,
    align: TextAlign = TextAlign.Start,
) {
    val text = remember(spans, palette, weight, italic, color) {
        annotate(spans, palette, weight, italic, color)
    }
    androidx.compose.foundation.text.ClickableText(
        text = text,
        // take the full width the window offers, so a rotation to landscape
        // reflows into the wider column instead of keeping the portrait measure
        modifier = Modifier.fillMaxWidth(),
        style = androidx.compose.ui.text.TextStyle(
            color = color,
            fontSize = size,
            lineHeight = lineHeight,
            textAlign = align,
        ),
        onClick = { offset ->
            text.getStringAnnotations(LINK_TAG, offset, offset).firstOrNull()
                ?.let { onLinkClick(it.item) }
        },
    )
}

private const val LINK_TAG = "kodelab.link"

private fun annotate(
    spans: List<Markdown.Span>,
    palette: EditorPalette,
    weight: FontWeight,
    italic: Boolean,
    color: androidx.compose.ui.graphics.Color,
): AnnotatedString = buildAnnotatedString {
    spans.forEach { s ->
        val style = SpanStyle(
            color = when {
                s.link != null -> palette.accent
                s.code -> palette.textPrimary
                else -> color
            },
            fontWeight = if (s.bold) FontWeight.Bold else weight,
            fontStyle = if (s.italic || italic) FontStyle.Italic else FontStyle.Normal,
            fontFamily = if (s.code) FontFamily.Monospace else null,
            background = if (s.code) palette.panel else androidx.compose.ui.graphics.Color.Unspecified,
            textDecoration = when {
                s.strike -> TextDecoration.LineThrough
                s.link != null -> TextDecoration.Underline
                else -> null
            },
        )
        if (s.link != null) pushStringAnnotation(LINK_TAG, s.link)
        withStyle(style) { append(s.text) }
        if (s.link != null) pop()
    }
}
