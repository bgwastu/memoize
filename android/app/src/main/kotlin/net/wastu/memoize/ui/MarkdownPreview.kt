package net.wastu.memoize.ui

import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
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
    color: Color, maxLines: Int) {
    val parsed by produceState(DisplayStyles(source, "", emptyList()), source) {
        value = withContext(Dispatchers.Default) { sourceStyles(source, "", 0, source.length.coerceAtMost(512)) }
    }
    val display = if (parsed.input == source) parsed else DisplayStyles(source, "", emptyList())
    val colors = MaterialTheme.colorScheme
    AndroidView(modifier = Modifier.fillMaxWidth(), factory = { context ->
        TextView(context).apply {
            includeFontPadding = false
            isFocusable = false
            setPadding(0, 0, 0, 0)
            ellipsize = TextUtils.TruncateAt.END
        }
    }, update = { view ->
        view.maxLines = maxLines
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSize.value)
        view.setLineHeight(TypedValue.COMPLEX_UNIT_SP, style.lineHeight.value)
        view.letterSpacing = style.letterSpacing.value / style.fontSize.value
        view.typeface = Typeface.create(Typeface.DEFAULT, style.fontWeight?.weight ?: 400, false)
        view.setTextColor(color.toArgb())
        view.text = nativeMarkdownPreview(display, colors, matches, view.paint)
    })
}

internal fun nativeMarkdownPreview(display: DisplayStyles, colors: ColorScheme,
    matches: List<TextMatch>, paint: TextPaint): SpannableString {
    val annotated = markdownPreview(display, colors, matches)
    return SpannableString(annotated.text).apply {
        annotated.spanStyles.forEach { range ->
            val style = range.item
            val spans = buildList<Any> {
                if (style.color != Color.Unspecified) add(ForegroundColorSpan(style.color.toArgb()))
                if (style.background != Color.Unspecified) add(BackgroundColorSpan(style.background.toArgb()))
                if (style.fontWeight == FontWeight.Bold) add(StyleSpan(Typeface.BOLD))
                if (style.fontStyle == FontStyle.Italic) add(StyleSpan(Typeface.ITALIC))
                if (style.textDecoration == TextDecoration.Underline) add(UnderlineSpan())
            }
            spans.forEach { setSpan(it, range.start, range.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        display.lists.forEach { paragraph ->
            val width = ceil(Layout.getDesiredWidth(this, paragraph.range.start, paragraph.contentStart, paint)).toInt()
            setSpan(LeadingMarginSpan.Standard(0, width), paragraph.range.start, paragraph.range.end, Spanned.SPAN_PARAGRAPH)
        }
    }
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
                SourceStyle.SEARCH -> search
            }
            addStyle(span, styled.range.start, styled.range.end)
        }
        matches.forEach { match -> addStyle(search, match.start, match.end) }
    }.toAnnotatedString()
}
