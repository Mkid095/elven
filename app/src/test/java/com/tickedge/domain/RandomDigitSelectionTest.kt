package com.tickedge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the random target-digit draw used by Strategy B.
 *
 * The rule that matters is not "is it random" — that is unassertable — but that a market never
 * immediately repeats its own previous draw. A plain uniform redraw repeats 1 time in 10, which to
 * a user watching a single market looks exactly like the digit got stuck, so the exclusion exists
 * deliberately and is pinned here.
 */
class RandomDigitSelectionTest {

    @Test
    fun `a draw never repeats the previous digit`() {
        // Exhaustively: for every possible previous value, no draw may return it.
        (0..9).forEach { previous ->
            repeat(300) {
                assertNotEquals(
                    "digit $previous was drawn twice in a row",
                    previous,
                    drawRandomDigit(previous)
                )
            }
        }
    }

    @Test
    fun `every draw stays within 0 to 9`() {
        repeat(2_000) {
            val drawn = drawRandomDigit(it % 10)
            assertTrue("drew $drawn, outside 0..9", drawn in 0..9)
        }
    }

    @Test
    fun `a first draw has no previous digit to avoid`() {
        // With previous == null all ten digits must remain reachable, otherwise the very first
        // draw on a market would be biased toward a subset.
        val seen = mutableSetOf<Int>()
        repeat(2_000) { seen += drawRandomDigit(null) }

        assertEquals("all ten digits should be reachable on a first draw", 10, seen.size)
    }

    @Test
    fun `the draw covers every other digit over many samples`() {
        val seen = mutableSetOf<Int>()
        repeat(500) { seen += drawRandomDigit(7) }

        assertEquals("every digit except 7 should be reachable", 9, seen.size)
    }
}
