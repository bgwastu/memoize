package net.wastu.memoize.ui

import androidx.compose.ui.text.TextRange
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
