package net.wastu.memoize.ui

import android.app.Application
import android.app.Activity
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.util.TypedValue
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import net.wastu.memoize.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NativeLongLineTest {
    private val long = "a".repeat(65_536) + "📝"

    @Test fun bulkSourceChoosesHorizontalLayoutWithoutChangingSourceOrSelection() {
        val view = editor(long)
        assertTrue("A pathological cache context must use the native wide layout before measuring", view.isHorizontallyScrollable)
        assertEquals(long, view.text.toString())
        assertEquals(long.length, view.selectionStart)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            controller.get().setContentView(view.parent as View)
            controller.visible()
            measure(view)
            assertTrue(requireNotNull(view.layout).width > view.width)
            val caret = view.layout.getPrimaryHorizontal(long.length) - view.scrollX + view.totalPaddingLeft
            assertTrue("A recreated end caret stays inside the horizontal viewport: caret=$caret scroll=${view.scrollX} width=${view.width}",
                caret in 0f..view.width.toFloat())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun nativeReplacementUndoAndClearRestoreOrdinaryWrappedEditing() {
        val ordinary = "- An ordinary readable list item with words. ".repeat(12)
        val view = editor(ordinary)
        view.setUseBoundsForWidth(true)
        measure(view)
        assertFalse(view.isHorizontallyScrollable)
        assertTrue(view.getUseBoundsForWidth())
        assertTrue(requireNotNull(view.layout).lineCount > 2)
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.textSize + 1f)
        assertNull("A presentation change invalidates the old narrow layout", view.layout)
        val input = requireNotNull(view.onCreateInputConnection(EditorInfo()))
        assertTrue(input.setSelection(0, ordinary.length))
        assertTrue(input.commitText(long, 1))
        assertTrue(view.isHorizontallyScrollable)
        assertFalse("Fallback must disable expensive glyph bounds before measurement", view.getUseBoundsForWidth())
        assertTrue("The old narrow layout is replaced before native mutation", requireNotNull(view.layout).width > view.width)
        assertEquals(long, view.text.toString())
        assertEquals(long.length, view.selectionStart)
        view.undo()
        assertEquals(ordinary, view.text.toString())
        assertFalse(view.isHorizontallyScrollable)
        assertTrue("Undo restores the original bounds mode for safe source", view.getUseBoundsForWidth())
        measure(view)
        assertTrue(requireNotNull(view.layout).lineCount > 2)
        view.redo()
        assertEquals(long, view.text.toString())
        assertTrue(view.isHorizontallyScrollable)
        assertFalse(view.getUseBoundsForWidth())
        view.scrollTo(500, 0)
        assertTrue(input.setSelection(0, long.length))
        assertTrue(input.commitText("", 1))
        assertEquals("", view.text.toString())
        assertFalse(view.isHorizontallyScrollable)
        assertTrue("Clearing the pathological source restores its original bounds mode", view.getUseBoundsForWidth())
        assertEquals(0, view.scrollX)
    }

    @Test fun ordinarySpaceCjkAndParagraphBoundariesKeepWrapping() {
        for (content in listOf("a".repeat(65_536), "word ".repeat(20_000), "猫".repeat(100_000),
                "a".repeat(40_000) + "\n" + "b".repeat(40_000))) {
            val view = editor(content)
            assertFalse(view.isHorizontallyScrollable)
            assertEquals(content, view.text.toString())
        }
    }

    private fun editor(content: String): NativeSourceView {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val view = NativeSourceView(ContextThemeWrapper(context, R.style.Theme_Memoize))
        FrameLayout(context).addView(view, FrameLayout.LayoutParams(320, 470))
        view.seed(content, TextRange(content.length), 0, NativeEditHistory())
        return view
    }

    private fun measure(view: NativeSourceView) {
        val host = view.parent as View
        host.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(470, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 320, 470)
        shadowOf(Looper.getMainLooper()).idle()
        if (host.isLayoutRequested) {
            host.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(470, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, 320, 470)
        }
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        view.computeScroll()
    }
}
