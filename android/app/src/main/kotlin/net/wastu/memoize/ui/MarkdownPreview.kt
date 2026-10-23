package net.wastu.memoize.ui

import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.LeadingMarginSpan
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.ceil

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.wastu.memoize.data.TextMatch

/** Read-only native paragraphs share editor spans without Compose's extra paragraph breaks. */
@Composable
internal fun MarkdownPreviewText(source: String, matches: List<TextMatch>, style: TextStyle,
    color: Color, maxLines: Int, fullSource: String = source, sourceOffset: Int = 0,
    onToggleTask: ((SourceEdit) -> Unit)? = null) {
    val parsed by produceState(sourceOffset to DisplayStyles(fullSource, "", emptyList()), fullSource, sourceOffset, source) {
        value = sourceOffset to withContext(Dispatchers.Default) {
            sourceStyles(fullSource, "", sourceOffset, (sourceOffset + source.length.coerceAtMost(512)).coerceAtMost(fullSource.length))
        }
    }
    val display = if (parsed.first == sourceOffset && parsed.second.input == fullSource)
        previewSourceStyles(parsed.second, source, sourceOffset) else DisplayStyles(source, "", emptyList())
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val sizePx = with(density) { style.fontSize.toPx() }
    val lineHeightPx = with(density) { style.lineHeight.toPx() }
    AndroidView(modifier = Modifier.fillMaxWidth().clipToBounds(), factory = { context ->
        TextView(context).apply {
            includeFontPadding = false
            isFocusable = false
            setPadding(0, 0, 0, 0)
            ellipsize = TextUtils.TruncateAt.END
        }
    }, update = { view ->
        view.maxLines = maxLines
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        view.setLineHeight(TypedValue.COMPLEX_UNIT_PX, lineHeightPx)
        view.letterSpacing = style.letterSpacing.value / style.fontSize.value
        view.typeface = sourceTypeface()
        view.setTextColor(color.toArgb())
        view.text = nativeMarkdownPreview(display, colors, matches, view.paint)
        var downX = 0f
        var downY = 0f
        var downTime = 0L
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downTime = event.eventTime
                    false
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val slop = android.view.ViewConfiguration.get(view.context).scaledTouchSlop
                    if (kotlin.math.abs(event.x - downX) <= slop && kotlin.math.abs(event.y - downY) <= slop &&
                        event.eventTime - downTime < android.view.ViewConfiguration.getLongPressTimeout()
                    ) {
                        val layout = view.layout
                        if (layout != null) {
                            val y = event.y.toInt()
                            if (y in 0 until layout.height) {
                                val line = layout.getLineForVertical(y)
                                val x = event.x
                                if (x >= layout.getLineLeft(line) && x <= layout.getLineRight(line)) {
                                    val offset = layout.getOffsetForHorizontal(line, x)
                                    if (onToggleTask != null) taskToggleAt(fullSource, sourceOffset + offset)?.let {
                                        onToggleTask(it)
                                        return@setOnTouchListener true
                                    }
                                    val link = findLinkAt(display, offset)
                                    if (link != null) {
                                        try {
                                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link.first)).apply {
                                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            view.context.startActivity(intent)
                                            return@setOnTouchListener true
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                        }
                    }
                    false
                }
                else -> false
            }
        }
    })
}

internal fun ColorScheme.sourceSyntaxColor(active: Boolean = false): Color =
    onSurfaceVariant.copy(alpha = if (active) 0.85f else 0.60f)

internal fun nativeMarkdownPreview(display: DisplayStyles, colors: ColorScheme,
    matches: List<TextMatch>, paint: TextPaint): SpannableString {
    val palette = SourcePalette(colors.primary.toArgb(), colors.secondary.toArgb(), colors.tertiary.toArgb(),
        colors.surfaceContainerHigh.toArgb(), colors.onTertiaryContainer.toArgb(), colors.tertiaryContainer.toArgb(),
        colors.sourceSyntaxColor().toArgb())
    return SpannableString(display.input.toString()).apply {
        display.ranges.forEach { styled ->
            sourceStyleSpans(styled.style, palette).forEach {
                setSpan(it, styled.range.start, styled.range.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        matches.forEach { match ->
            sourceStyleSpans(SourceStyle.SEARCH, palette).forEach {
                setSpan(it, match.start, match.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        display.lists.forEach { paragraph ->
            val width = ceil(Layout.getDesiredWidth(this, paragraph.range.start, paragraph.contentStart, paint)).toInt()
            setSpan(LeadingMarginSpan.Standard(0, width), paragraph.range.start, paragraph.range.end, Spanned.SPAN_PARAGRAPH)
        }
    }
}

/** Project source offsets without reinterpreting clipped Markdown or changing visible text. */
internal fun previewSourceStyles(display: DisplayStyles, source: String, offset: Int): DisplayStyles {
    val end = offset + source.length
    val ranges = display.ranges.mapNotNull { styled ->
        val start = maxOf(offset, styled.range.start)
        val stop = minOf(end, styled.range.end)
        if (start < stop) StyledRange(TextMatch(start - offset, stop - offset), styled.style) else null
    }
    val lists = display.lists.mapNotNull { paragraph ->
        val start = maxOf(offset, paragraph.range.start)
        val stop = minOf(end, paragraph.range.end)
        if (start < stop) ListParagraph(TextMatch(start - offset, stop - offset),
            (paragraph.contentStart - offset).coerceIn(start - offset, stop - offset)) else null
    }
    return DisplayStyles(source, display.query, ranges, lists)
}

/** Search spans come last so matches keep their readable colors over Markdown styling. */
internal fun markdownPreview(display: DisplayStyles, colors: ColorScheme, matches: List<TextMatch>): AnnotatedString {
    val source = display.input.toString()
    val search = SpanStyle(background = colors.tertiaryContainer, color = colors.onTertiaryContainer)
    return AnnotatedString.Builder(source).apply {
        display.ranges.forEach { styled ->
            val span = when (styled.style) {
                SourceStyle.HEADING -> SpanStyle(color = colors.primary, fontWeight = FontWeight.Bold)
                SourceStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                SourceStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                SourceStyle.CODE -> SpanStyle(color = colors.secondary, background = colors.surfaceContainerHigh)
                SourceStyle.TAG -> SpanStyle(color = colors.tertiary)
                SourceStyle.LINK -> SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)
                SourceStyle.SYNTAX -> SpanStyle(color = colors.sourceSyntaxColor(), fontWeight = FontWeight.Normal, fontStyle = FontStyle.Normal)
                SourceStyle.SEARCH -> search
            }
            addStyle(span, styled.range.start, styled.range.end)
        }
        matches.forEach { match -> addStyle(search, match.start, match.end) }
    }.toAnnotatedString()
}
