package com.aura.launcher

import com.aura.launcher.ui.home.DIGIT_GLYPHS
import com.aura.launcher.ui.home.GLYPH_COLUMNS
import com.aura.launcher.ui.home.GLYPH_ROWS
import com.aura.launcher.ui.home.MATRIX_COLUMNS
import com.aura.launcher.ui.home.matrixColumns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dot-matrix clock face is a hand-drawn font laid out by hand, and both
 * halves of that are easy to get quietly wrong: a glyph a row short, or a digit
 * stamped one column past the end of the grid where it is silently dropped. A
 * clock that shows three of its four digits is not a clock.
 */
class ClockMatrixTest {

    @Test
    fun `every digit is drawn on the same grid`() {
        for (digit in '0'..'9') {
            val glyph = DIGIT_GLYPHS[digit]
            assertTrue("no glyph for $digit", glyph != null)
            assertEquals("wrong row count for $digit", GLYPH_ROWS, glyph!!.size)
            for (row in glyph) {
                assertEquals("wrong column count for $digit", GLYPH_COLUMNS, row.length)
                assertTrue("$digit has a lamp that is neither on nor off", row.all { it == '0' || it == '1' })
            }
            assertTrue("$digit is blank", glyph.any { row -> row.contains('1') })
        }
    }

    @Test
    fun `the layout fills the grid it declares`() {
        val grid = matrixColumns("0842")
        assertEquals(MATRIX_COLUMNS, grid.size)
        assertTrue(grid.all { it.size == GLYPH_ROWS })
    }

    @Test
    fun `all four digits land on the board`() {
        // 1 is the sparsest glyph, so four of them is the weakest case: if a
        // digit were being stamped off the end, this is where it would show.
        val grid = matrixColumns("1111")
        val litColumns = grid.indices.filter { column -> grid[column].any { it } }

        // The four digits and the colon, and nothing outside the grid.
        assertTrue("the last digit is missing", litColumns.max() >= MATRIX_COLUMNS - GLYPH_COLUMNS)
        assertTrue("the first digit is missing", litColumns.min() < GLYPH_COLUMNS)
    }

    @Test
    fun `the colon sits between the hour and the minute`() {
        val grid = matrixColumns("0000")
        val colon = GLYPH_COLUMNS * 2 + 2
        assertTrue("no colon", grid[colon][2] && grid[colon][4])
        // And the columns either side of it are the gap, not a stray digit.
        assertFalse(grid[colon - 1].any { it })
        assertFalse(grid[colon + 1].any { it })
    }

    @Test
    fun `each digit lights a different pattern`() {
        // Two faces that draw the same dots would mean the glyph table has a
        // copy-paste in it — the sort of thing nobody notices until 6 o'clock.
        val patterns = ('0'..'9').map { digit -> matrixColumns("${digit}000").take(GLYPH_COLUMNS).map { it.toList() } }
        assertEquals(10, patterns.toSet().size)
    }

    @Test
    fun `an unexpected time leaves the board dark rather than crashing`() {
        val grid = matrixColumns("--:-")
        assertEquals(MATRIX_COLUMNS, grid.size)
    }
}
