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

    /**
     * A digit on a cool-off must not be re-rolled. Drawing it and filtering afterwards would waste
     * the draw and leave that market reading as if it had failed the condition, when it had not.
     */
    @Test
    fun `a parked digit is never drawn`() {
        val parked = setOf(3, 8)
        repeat(1_000) {
            assertTrue("drew ${drawRandomDigit(5, parked)}, which is parked", drawRandomDigit(5, parked) !in parked)
        }
    }

    @Test
    fun `when one digit is free it is drawn even though every other is parked`() {
        // Nine of ten digits parked and the previous draw is a parked one, so the strict pool is
        // empty. The fallback must return the single legal digit rather than nothing.
        repeat(200) {
            assertEquals(
                "the only digit still tradeable",
                4,
                drawRandomDigit(7, (0..9).filter { it != 4 }.toSet())
            )
        }
    }

    /**
     * The other fallback, and the one that is genuinely a trade-off rather than a convenience: when
     * the previous draw is itself the only free digit, no-legal-non-repeat exists. Repeating wins,
     * because a repeat is merely a cosmetic stutter while a parked digit is a refused order. The
     * sweep's cool-off check is what actually blocks that order.
     */
    @Test
    fun `a repeat beats a parked digit when only the previous digit is free`() {
        repeat(200) {
            assertEquals(
                "the only digit still tradeable, even though it repeats",
                7,
                drawRandomDigit(7, (0..9).filter { it != 7 }.toSet())
            )
        }
    }

    /**
     * Parking every digit is not a state the market can trade in, but it must not crash or silently
     * drop the market out of the sweep — the draw falls back to the full range and the sweep's
     * cool-off check is what actually refuses the order.
     */
    @Test
    fun `parking every digit falls back to the full range instead of failing`() {
        val drawn = drawRandomDigit(null, (0..9).toSet())
        assertTrue("drew $drawn, outside 0..9", drawn in 0..9)
    }

    @Test
    fun `a parked digit never displaces a digit that is merely the previous one`() {
        // previous == a parked digit is contradictory state; the parked set must still win, since
        // the trade would be refused either way and a repeated digit is the lesser problem.
        repeat(200) {
            assertTrue(drawRandomDigit(3, setOf(3)) !in setOf(3))
        }
    }
}
