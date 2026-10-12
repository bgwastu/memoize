package net.wastu.memoize.ui

import android.app.Application
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import net.wastu.memoize.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class NativeFormattingTest {
    @Test fun caretFormattingKeepsNativeInputInsideMarkersAndHasOneCommandUndo() {
        val original = "cat 🐈"
        val view = editor(original, TextRange(original.length))
        val input = requireNotNull(view.onCreateInputConnection(EditorInfo()))
        view.format(SourceFormat.BOLD)
        assertEquals(original + "****", view.text.toString())
        assertEquals(original.length + 2, view.selectionStart)
        assertTrue(input.commitText("bold", 1))
        assertEquals(original + "**bold**", view.text.toString())
        view.undo()
        assertEquals(original + "****", view.text.toString())
        view.undo()
        assertEquals(original, view.text.toString())
        assertEquals(original.length, view.selectionStart)
        view.redo()
        assertEquals(original + "****", view.text.toString())
    }

    @Test fun formattingAndTogglingPreserveReversedUnicodeSelection() {
        val original = "before 📝猫 after"
        val start = original.indexOf("📝")
        val end = original.indexOf(" after")
        val view = editor(original, TextRange(end, start))
        view.format(SourceFormat.BOLD)
        assertEquals("before **📝猫** after", view.text.toString())
        assertEquals(end + 2, view.selectionStart)
        assertEquals(start + 2, view.selectionEnd)
        view.format(SourceFormat.BOLD)
        assertEquals(original, view.text.toString())
        assertEquals(end, view.selectionStart)
        assertEquals(start, view.selectionEnd)
        view.undo()
        assertEquals("before **📝猫** after", view.text.toString())
        view.undo()
        assertEquals(original, view.text.toString())
    }

    @Test fun listConversionPreservesCrLfAndUnicodeAndUndoesAsOneNativeCommand() {
        val original = "cat 🐈\r\ndog 🐕\r\nuntouched"
        val end = original.indexOf("\r\nuntouched")
        val view = editor(original, TextRange(end, 0))
        view.formatBlock(SourceBlockFormat.NUMBERED)
        assertEquals("1. cat 🐈\r\n2. dog 🐕\r\nuntouched", view.text.toString())
        assertEquals(end + 6, view.selectionStart)
        assertEquals(3, view.selectionEnd)
        assertFalse("Ordinary list formatting keeps native wrapping", view.isHorizontallyScrollable)
        view.undo()
        assertEquals(original, view.text.toString())
        assertEquals(end, view.selectionStart)
        assertEquals(0, view.selectionEnd)
        view.redo()
        assertEquals("1. cat 🐈\r\n2. dog 🐕\r\nuntouched", view.text.toString())
    }

    @Test fun inlineCodeTogglesTheExactLongerOrPaddedNativeWrapper() {
        for ((original, formatted) in listOf("a`b 🐈" to "``a`b 🐈``", "`a`" to "`` `a` ``", " a`b " to "``  a`b  ``")) {
            val view = editor(original, TextRange(original.length, 0))
            view.format(SourceFormat.CODE)
            assertEquals(formatted, view.text.toString())
            view.format(SourceFormat.CODE)
            assertEquals(original, view.text.toString())
            assertEquals(original.length, view.selectionStart)
            assertEquals(0, view.selectionEnd)
            view.undo()
            assertEquals(formatted, view.text.toString())
            view.undo()
            assertEquals(original, view.text.toString())
        }
    }

    @Test fun indentRequiresListContextAndOutdentRequiresExistingIndent() {
        assertNull(listIndent("plain text", TextRange(5), false))
        assertNull(listIndent("- top level", TextRange(4), true))
        val original = "- parent\n- child 🐈"
        val view = editor(original, TextRange(original.length))
        view.indent(false)
        val nested = "- parent\n  - child 🐈"
        assertEquals(nested, view.text.toString())
        assertTrue(listIndent(nested, TextRange(nested.length), true) != null)
        view.indent(true)
        assertEquals(original, view.text.toString())
        view.undo()
        assertEquals(nested, view.text.toString())
        view.undo()
        assertEquals(original, view.text.toString())
    }

    @Test fun headingAndChecklistCommandsPreserveTheListBodyAndExplicitLineBreaks() {
        val original = "- parent\n  - [x] child 🐈\nnext"
        val caret = original.indexOf("child") + "child 🐈".length
        val view = editor(original, TextRange(caret))
        view.formatBlock(SourceBlockFormat.HEADING_2)
        assertEquals("- parent\n  - [x] ## child 🐈\nnext", view.text.toString())
        view.formatBlock(SourceBlockFormat.PARAGRAPH)
        assertEquals(original, view.text.toString())
        view.undo()
        assertEquals("- parent\n  - [x] ## child 🐈\nnext", view.text.toString())
        view.undo()
        assertEquals(original, view.text.toString())
        val plain = editor("task 📝", TextRange(7))
        plain.formatBlock(SourceBlockFormat.CHECKLIST)
        assertEquals("- [ ] task 📝", plain.text.toString())
        plain.undo()
        assertEquals("task 📝", plain.text.toString())
    }

    @Test fun composingTextIsLeftIntactUntilTheImeFinishesItsNativeTransaction() {
        val original = "- composing 🐈"
        val view = editor(original, TextRange(2, original.length))
        val input = requireNotNull(view.onCreateInputConnection(EditorInfo()))
        assertTrue(input.setComposingRegion(2, original.length))
        view.format(SourceFormat.ITALIC)
        view.formatBlock(SourceBlockFormat.HEADING_1)
        view.indent(false)
        assertEquals(original, view.text.toString())
        assertEquals(2, BaseInputConnection.getComposingSpanStart(view.text))
        assertEquals(original.length, BaseInputConnection.getComposingSpanEnd(view.text))
        assertTrue(input.finishComposingText())
        view.format(SourceFormat.ITALIC)
        assertEquals("- *composing 🐈*", view.text.toString())
        view.undo()
        assertEquals(original, view.text.toString())
    }

    @Test fun paragraphCommandsCannotRewriteFencedOrIndentedCode() {
        for (original in listOf("```\ncode 📝\n```", "    code 📝", "- ```\n  code 📝\n  ```")) {
            val caret = original.indexOf("code")
            val view = editor(original, TextRange(caret))
            view.formatBlock(SourceBlockFormat.BULLETED)
            view.formatBlock(SourceBlockFormat.HEADING_1)
            assertEquals(original, view.text.toString())
            assertEquals(caret, view.selectionStart)
        }
    }

    private fun editor(content: String, selection: TextRange): NativeSourceView {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val view = NativeSourceView(ContextThemeWrapper(context, R.style.Theme_Memoize))
        val host = FrameLayout(context)
        host.addView(view, FrameLayout.LayoutParams(320, 470))
        view.seed(content, selection, 0, NativeEditHistory())
        host.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(470, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 320, 470)
        shadowOf(Looper.getMainLooper()).idle()
        return view
    }
}
