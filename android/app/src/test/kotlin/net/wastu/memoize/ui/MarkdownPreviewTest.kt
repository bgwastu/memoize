package net.wastu.memoize.ui

import android.app.Application
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.runBlocking
import net.wastu.memoize.data.TextMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownPreviewTest {
    private val colors = lightColorScheme()

    @Test fun literalUnicodeSourceKeepsEditorRolesAndSearchWinsOverCode() = runBlocking {
        val source = "# Heading\n**bold** *italic* `cafe\u0301 🐈` #work/project"
        val display = sourceStyles(source, "", 0, source.length)
        val start = source.indexOf("cafe")
        val match = TextMatch(start, source.indexOf('`', start))
        val preview = markdownPreview(display, colors, listOf(match))
        assertEquals(source, preview.text)
        assertTrue(preview.spanStyles.any { it.item.color == colors.primary && it.item.fontWeight == FontWeight.Bold })
        assertTrue(preview.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start == source.indexOf("**") })
        assertTrue(preview.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        assertTrue(preview.spanStyles.any { it.item.color == colors.secondary && it.item.background == colors.surfaceContainerHigh })
        assertTrue(preview.spanStyles.any { it.item.color == colors.tertiary })
        val search = preview.spanStyles.last()
        assertEquals(match.start, search.start)
        assertEquals(match.end, search.end)
        assertEquals(colors.onTertiaryContainer, search.item.color)
        assertEquals(colors.tertiaryContainer, search.item.background)
        assertEquals(null, search.item.textDecoration)
        assertEquals(null, search.item.fontWeight)
    }

    @Test fun nativeParagraphsKeepConsecutiveListSpacingAndExactUnicode() = runBlocking {
        val source = "- cafe\u0301 🐈\r\n- next\n\n```md\n- literal code\n```\nPlain\n"
        val paint = TextPaint().apply { textSize = 20f }
        val display = sourceStyles(source, "", 0, source.length)
        val preview = nativeMarkdownPreview(display, colors, emptyList(), paint)
        assertEquals(source, preview.toString())
        assertEquals(2, preview.getSpans(0, preview.length, LeadingMarginSpan::class.java).size)
        fun layout(text: CharSequence) = StaticLayout.Builder.obtain(text, 0, text.length, paint, 600)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
        assertEquals("Styling must not invent empty paragraphs", layout(source).lineCount, layout(preview).lineCount)
    }

    @Test fun nativeWrappedListContinuationsAlignUnderTheirOwnPrefix() = runBlocking {
        val source = "- " + "words ".repeat(12) + "\n  12. " + "child ".repeat(12) + "\n      - [x] " + "task ".repeat(12)
        val paint = TextPaint().apply { textSize = 20f }
        val preview = nativeMarkdownPreview(sourceStyles(source, "", 0, source.length), colors, emptyList(), paint)
        assertEquals(source, preview.toString())
        val layout = StaticLayout.Builder.obtain(preview, 0, preview.length, paint, 220).setIncludePad(false).build()
        for (prefix in listOf("- ", "  12. ", "      - [x] ")) {
            val start = source.indexOf(prefix)
            val first = layout.getLineForOffset(start)
            val contentLeft = layout.getPrimaryHorizontal(start + prefix.length)
            assertTrue("Each list item wraps", layout.getLineStart(first + 1) > start)
            assertEquals("Wrapped content aligns with its own marker", contentLeft, layout.getPrimaryHorizontal(layout.getLineStart(first + 1)), 1f)
        }
    }
}
