package com.ikemenselect.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SystemDefTests {

    @Test
    fun replacesValuesKeepingCommentsAndOtherSections() {
        val src = "[Info]\nname = x\n\n[Select Info]\nrows = 2 ; linhas\ncolumns=5\ncell.size = 29,29\n\n[Other]\nrows = 99\n"
        val want = "[Info]\nname = x\n\n[Select Info]\nrows = 10 ; linhas\ncolumns=8\ncell.size = 29,29\n\n[Other]\nrows = 99\n"
        assertEquals(want, SystemDefEdit.setGridSize(src, 10, 8))
    }

    @Test
    fun addsMissingKeysInsideTheSection() {
        val src = "[Select Info]\ncell.size = 29,29\n\n[Other]\nx=1"
        val want = "[Select Info]\ncell.size = 29,29\nrows = 3\ncolumns = 4\n\n[Other]\nx=1"
        assertEquals(want, SystemDefEdit.setGridSize(src, 3, 4))
    }

    @Test
    fun appendsTheSectionWhenMissing() {
        assertEquals("[Files]\nsel=1\n\n[Select Info]\nrows = 3\ncolumns = 4\n", SystemDefEdit.setGridSize("[Files]\nsel=1\n", 3, 4))
    }

    @Test
    fun ignoresCommentedKeysKeepsCrLfAndIsIdempotent() {
        val src = "[Select Info]\r\n;rows = 1\r\nrows = 2\r\ncolumns = 5\r\n"
        val once = SystemDefEdit.setGridSize(src, 7, 9)
        assertEquals("[Select Info]\r\n;rows = 1\r\nrows = 7\r\ncolumns = 9\r\n", once)
        assertEquals(once, SystemDefEdit.setGridSize(once, 7, 9))
    }
}
