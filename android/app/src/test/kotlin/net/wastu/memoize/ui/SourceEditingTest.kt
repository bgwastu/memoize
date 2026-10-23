package net.wastu.memoize.ui

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceEditingTest {
    @Test fun mixedSelectionsLeavePlainTextAndFencedSourceUnchanged() {
        val sources = listOf(
            "- item\nplain text",
            "  - item\n  plain text",
            "- item\n```\n- fenced source\n```",
            "  - item\n  ```\n  - fenced source\n  ```",
            "- item\n- ```\n- fenced source\n- ```",
        )
        for (source in sources) for (outdent in listOf(false, true)) {
            assertNull("Mixed forward selections cannot alter non-list or fenced source",
                listIndent(source, TextRange(0, source.length), outdent))
            assertNull("Mixed reverse selections cannot alter non-list or fenced source",
                listIndent(source, TextRange(source.length, 0), outdent))
        }
    }
}

class TaskToggleTest {
    @Test fun togglesOnlyOnMarker() {
        val s = "- [ ] a\n- [x] b\n- c\n```\n- [ ] d\n```"
        assertEquals("x", taskToggleAt(s, 3)?.replacement)
        assertEquals(" ", taskToggleAt(s, 11)?.replacement)
        assertEquals(null, taskToggleAt(s, 7))
        assertEquals(null, taskToggleAt(s, 18))
        assertEquals(null, taskToggleAt(s, 30))
    }
}
