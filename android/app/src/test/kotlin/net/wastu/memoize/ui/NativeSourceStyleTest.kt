package net.wastu.memoize.ui

import android.app.Application
import android.graphics.Color
import android.os.Looper
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.UnderlineSpan
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import net.wastu.memoize.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeSourceStyleTest {
    private val words = "A readable list item wraps across the editor viewport. ".repeat(5)

    @Test fun existingUnderlineSurvivesSearchRestylingWithoutAdditionalUnderlines() {
        val content = "cafe\u0301 keeps cafe\u0301 🐈"
        val editor = editor(content)
        val input = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
        assertTrue(input.setSelection(0, 5))
        assertTrue(input.setComposingRegion(0, 5))
        val underline = UnderlineSpan()
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        editor.text.setSpan(underline, 0, 5, flags)
        // The native IME can already supply its own composing underline as well.
        val originalUnderlines = editor.text.getSpans(0, editor.text.length, UnderlineSpan::class.java).map {
            it to Triple(editor.text.getSpanStart(it), editor.text.getSpanEnd(it), editor.text.getSpanFlags(it))
        }
        var changes = 0
        editor.onCharactersChanged = { changes++ }
        val palette = SourcePalette(Color.BLUE, Color.GRAY, Color.MAGENTA, Color.LTGRAY, Color.BLACK, Color.YELLOW)

        for (query in listOf("cafe\u0301", "keeps", "")) {
            val source = editor.snapshot().content
            editor.style(runBlocking { sourceStyles(source, query, 0, source.length) }, palette)
            val body = editor.text
            val underlines = body.getSpans(0, body.length, UnderlineSpan::class.java)
            assertEquals("Search must not add its own underline", originalUnderlines.size, underlines.size)
            originalUnderlines.forEach { (span, bounds) ->
                assertTrue("Restyling keeps every existing native underline", underlines.any { it === span })
                assertEquals(bounds, Triple(body.getSpanStart(span), body.getSpanEnd(span), body.getSpanFlags(span)))
            }
            assertEquals(0, body.getSpanStart(underline))
            assertEquals(5, body.getSpanEnd(underline))
            assertEquals(flags, body.getSpanFlags(underline))
            assertEquals("Search background still follows the query", query.isNotEmpty(),
                body.getSpans(0, body.length, BackgroundColorSpan::class.java).any { it.backgroundColor == palette.searchBackground })
            assertEquals(content, body.toString())
            assertEquals(0, editor.selectionStart)
            assertEquals(5, editor.selectionEnd)
            assertEquals(0, BaseInputConnection.getComposingSpanStart(body))
            assertEquals(5, BaseInputConnection.getComposingSpanEnd(body))
        }
        assertEquals("Search restyling cannot publish a character edit", 0, changes)
    }

    @Test fun wrappedListLinesAlignWithItemTextWithoutChangingSourceOrSelection() {
        val content = "- $words"
        val editor = editor(content)
        editor.setSelection(4, 12)
        decorate(editor)
        val layout = requireNotNull(editor.layout)
        assertTrue("The real native paragraph must wrap", layout.lineCount > 2)
        val contentLeft = layout.getPrimaryHorizontal(2)
        assertEquals("Keep the visible raw marker on the first line", 0f, layout.getPrimaryHorizontal(0), 0.5f)
        for (line in 1 until layout.lineCount) {
            assertEquals("Wrapped line $line starts below item text", contentLeft,
                layout.getPrimaryHorizontal(layout.getLineStart(line)), 1f)
        }
        assertEquals(content, editor.text.toString())
        assertEquals(4, editor.selectionStart)
        assertEquals(12, editor.selectionEnd)
    }

    @Test fun nestedOrderedTaskAndTabPrefixesAlignWithTheirOwnItemText() {
        val prefixes = listOf("- ", "  12. ", "      - [x] ", "      - [ ] ")
        val content = prefixes.joinToString("\n") { it + words }
        val editor = editor(content)
        decorate(editor)
        var start = 0
        for (prefix in prefixes) {
            assertWrapped(editor, start, start + prefix.length)
            start += prefix.length + words.length + 1
        }
        val tabs = editor("- parent\n\t- [x]\t$words")
        decorate(tabs)
        assertWrapped(tabs, "- parent\n".length, "- parent\n\t- [x]\t".length)
        assertEquals(content, editor.text.toString())
    }

    @Test fun fencedAndIndentedCodeRemainPlainEvenWithListLookingPrefixes() {
        val sources = listOf(
            "    - $words\n      - $words",
            "- parent\n\n      - $words\n        - $words",
            "```\n- $words\n```",
            "- ```\n  - $words\n  ```",
            "~~~\n- $words\n~~~",
        )
        for (content in sources) {
            val editor = editor(content)
            decorate(editor)
            val display = runBlocking { sourceStyles(content, "", 0, content.length) }
            val expected = if (content.startsWith("- parent")) listOf(0) else emptyList()
            assertEquals("Code cannot create list paragraph annotations", expected, display.lists.map { it.range.start })
            val native = requireNotNull(editor.layout)
            for (line in 1 until native.lineCount) {
                val offset = native.getLineStart(line)
                if (offset > 0 && content[offset - 1] != '\n') {
                    assertEquals("Code wrapping keeps its native margin", 0f, native.getPrimaryHorizontal(offset), 1f)
                }
            }
            assertEquals(content, editor.text.toString())
        }
    }

    @Test fun paragraphMarginsStayLocalAndMarkerRemovalCanBeUndone() {
        val content = "- $words\r\n\r\n$words"
        val editor = editor(content)
        decorate(editor)
        assertWrapped(editor, 0, 2)
        val plain = content.lastIndexOf(words)
        val native = requireNotNull(editor.layout)
        for (line in native.getLineForOffset(plain) until native.lineCount) {
            assertEquals("A list margin cannot bleed into a plain paragraph", 0f,
                native.getPrimaryHorizontal(native.getLineStart(line)), 1f)
        }
        val input = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
        assertTrue(input.setSelection(0, 2))
        assertTrue(input.commitText("", 1))
        decorate(editor)
        assertEquals(content.drop(2), editor.text.toString())
        assertEquals(0f, requireNotNull(editor.layout).getPrimaryHorizontal(editor.layout.getLineStart(1)), 1f)
        editor.undo()
        decorate(editor)
        assertEquals(content, editor.text.toString())
        assertWrapped(editor, 0, 2)
        editor.redo()
        decorate(editor)
        assertEquals(content.drop(2), editor.text.toString())
    }

    @Test fun fontRestylingPreservesCompositionAndRecomputesTheMargin() {
        val content = "- [x] $words"
        val editor = editor(content)
        decorate(editor)
        val before = requireNotNull(editor.layout).getPrimaryHorizontal(6)
        var changes = 0
        editor.onCharactersChanged = { changes++ }
        val input = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
        assertTrue(input.setSelection(8, 15))
        assertTrue(input.setComposingRegion(6, 20))
        editor.present(SourcePresentation(30f, 40f, 0.02f, Color.BLACK, Color.GRAY, Color.BLUE, Color.CYAN))
        layout(editor)
        assertWrapped(editor, 0, 6)
        assertTrue(requireNotNull(editor.layout).getPrimaryHorizontal(6) > before)
        assertEquals(6, BaseInputConnection.getComposingSpanStart(editor.text))
        assertEquals(20, BaseInputConnection.getComposingSpanEnd(editor.text))
        assertEquals(8, editor.selectionStart)
        assertEquals(15, editor.selectionEnd)
        assertEquals(content, editor.text.toString())
        assertEquals("Paragraph styling must not create a character epoch", 0, changes)
    }

    @Test fun aViewportInsideALongItemRetainsOnlyIntersectingParagraphMetadata() {
        val earlier = "- earlier item\n".repeat(500)
        val content = earlier + "- " + words.repeat(50) + "\n- later item"
        val anchor = earlier.length + 2 + words.length * 25
        val display = runBlocking { sourceStyles(content, "", anchor, anchor + 300) }
        assertEquals(1, display.lists.size)
        assertEquals(earlier.length, display.lists.single().range.start)
        assertEquals(earlier.length + 2, display.lists.single().contentStart)
        assertEquals(content.indexOf("\n- later item") + 1, display.lists.single().range.end)
        assertTrue("Keep the original input identity for asynchronous admission", display.input === content)
        val fence = "```\n" + "- code item\n".repeat(500) + "```"
        assertTrue(runBlocking { sourceStyles(fence, "", fence.length - 300, fence.length - 100) }.lists.isEmpty())
    }

    private fun assertWrapped(editor: NativeSourceView, paragraph: Int, itemText: Int) {
        val native = requireNotNull(editor.layout)
        val first = native.getLineForOffset(paragraph)
        val end = editor.text.indexOf('\n', itemText).let { if (it < 0) editor.length() else it }
        val last = native.getLineForOffset(end - 1)
        assertTrue("The actual item must wrap", last > first)
        val left = native.getPrimaryHorizontal(itemText)
        for (line in first + 1..last) {
            assertEquals("Every wrapped glyph aligns below this item text", left,
                native.getPrimaryHorizontal(native.getLineStart(line)), 1f)
        }
    }

    private fun editor(content: String): NativeSourceView {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val editor = NativeSourceView(ContextThemeWrapper(context, R.style.Theme_Memoize))
        editor.present(SourcePresentation(20f, 28f, 0.01f, Color.BLACK, Color.GRAY, Color.BLUE, Color.CYAN))
        val host = FrameLayout(context)
        host.addView(editor, FrameLayout.LayoutParams(320, 600))
        editor.seed(content, TextRange(0), 0, NativeEditHistory())
        shadowOf(Looper.getMainLooper()).idle()
        layout(editor)
        return editor
    }

    private fun decorate(editor: NativeSourceView) {
        val content = editor.snapshot().content
        editor.style(runBlocking { sourceStyles(content, "", 0, content.length) },
            SourcePalette(Color.BLUE, Color.GRAY, Color.MAGENTA, Color.LTGRAY, Color.BLACK, Color.YELLOW))
        layout(editor)
    }

    private fun layout(editor: NativeSourceView) {
        val host = editor.parent as FrameLayout
        editor.forceLayout()
        host.forceLayout()
        host.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 320, 600)
    }
}
