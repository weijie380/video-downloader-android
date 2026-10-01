package com.videodl.app.ytdlp

import org.junit.Assert.*
import org.junit.Test

class GallerySelectionTest {
    @Test fun keepsOriginalOrderAndDeduplicates() {
        val selection = GallerySelection.create(5, listOf(4, 1, 4))!!
        assertEquals(listOf(1, 4), GallerySelection.decode(selection.encode())!!.indices)
        assertEquals("已选 2/5 张图片", selection.label)
    }
    @Test fun oldAndEmptySelectionsNeverMeanAll() {
        assertNull(GallerySelection.decode(null))
        assertEquals(emptyList<Int>(), GallerySelection.decode("5|")!!.indices)
    }
    @Test fun rejectsCorruptedOrOutOfRangeSelections() {
        listOf("5|5", "5|-1", "0|", "5|a", "5|1|2", "|1").forEach {
            assertNull(it, GallerySelection.decode(it))
        }
    }
    @Test fun detectsChangedPhotoCount() {
        assertFalse(GallerySelection.decode("5|1,4")!!.matches(4))
    }
}
