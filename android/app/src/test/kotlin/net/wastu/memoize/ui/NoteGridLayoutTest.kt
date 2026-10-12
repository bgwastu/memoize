package net.wastu.memoize.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteGridLayoutTest {
    @Test fun capacityScalesWithRealWidthAndTextSizeWhileDefaultsStayReadable() {
        assertEquals(2, noteGridCapacity(384f, 1f))
        assertEquals(1, noteGridCapacity(384f, 2f))
        assertEquals(4, noteGridCapacity(840f, 1f))
        assertEquals(4, noteGridCapacity(1_600f, 1f))
        assertEquals(2, noteGridCapacity(840f, 2f))
        assertEquals(1, noteGridCapacity(100f, 1f))
        assertEquals(2, noteGridColumns(0, 384f, 1f))
        assertEquals(3, noteGridColumns(0, 840f, 1f))
        assertEquals(1, noteGridColumns(0, 840f, 2f))
        assertEquals(1, noteGridColumns(4, 384f, 2f))
        assertEquals(2, noteGridColumns(4, 384f, 1f))
    }

    @Test fun deliberateZoomChangesOneColumnAndHonorsBothLimits() {
        assertEquals(1, pinchedNoteColumns(2, 3, 1.25f))
        assertEquals(3, pinchedNoteColumns(2, 3, 0.75f))
        assertEquals(2, pinchedNoteColumns(2, 3, 1.05f))
        assertEquals(1, pinchedNoteColumns(1, 3, 1.25f))
        assertEquals(3, pinchedNoteColumns(3, 3, 0.75f))
        assertEquals(1, pinchedNoteColumns(1, 1, 0.75f))
    }
}
