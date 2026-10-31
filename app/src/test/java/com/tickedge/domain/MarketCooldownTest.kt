package com.tickedge.domain

import com.tickedge.data.model.TradeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The post-loss cool-off: after an exact contract loses repeatedly, the bot stops taking it for a
 * fixed number of trades regardless of how good its statistics look.
 *
 * These are the rules that decide whether real money goes into a market that has just been taking
 * money, so each expectation is derived by hand from the sequence described rather than read back
 * off the implementation.
 */
class MarketCooldownTest {

    private val vol25Differs2 = TradeEntry("R_25", 2, TradeType.DIFFERS)

    private fun tracker(lossesToCool: Int = 3, trades: Int = 5) =
        MarketCooldownTracker(lossesToCool, trades)

    private fun MarketCooldownTracker.lose(entry: TradeEntry, times: Int) =
        repeat(times) { record(entry, isWin = false) }

    @Test
    fun `nothing is restricted before the engine has traded anything`() {
        val cooldown = tracker()

        assertNull(cooldown.state(vol25Differs2))
        assertTrue(cooldown.coolingOff.value.isEmpty())
        assertEquals(0, cooldown.parkedDigits("R_25", TradeType.DIFFERS).size)
    }

    @Test
    fun `one loss is recorded but does not restrict the entry`() {
        val cooldown = tracker()
        cooldown.lose(vol25Differs2, 1)

        assertEquals(1, cooldown.state(vol25Differs2)?.losses)
        assertFalse(cooldown.state(vol25Differs2)!!.isCoolingOff)
        assertTrue(cooldown.coolingOff.value.isEmpty())
    }

    @Test
    fun `two losses still do not restrict the entry`() {
        val cooldown = tracker()
        cooldown.lose(vol25Differs2, 2)

        assertEquals(2, cooldown.state(vol25Differs2)?.losses)
        assertTrue(cooldown.coolingOff.value.isEmpty())
    }

    @Test
    fun `the third loss opens a cool-off of five trades`() {
        val cooldown = tracker()
        cooldown.lose(vol25Differs2, 3)

        assertTrue(cooldown.state(vol25Differs2)!!.isCoolingOff)
        assertEquals(5, cooldown.state(vol25Differs2)?.tradesRemaining)
        assertEquals(listOf(vol25Differs2), cooldown.coolingOff.value)
    }

    @Test
    fun `a cool-off decays by one for every trade taken anywhere`() {
        val cooldown = tracker()
        cooldown.lose(vol25Differs2, 3)

        repeat(2) { cooldown.advancePastTrade() }

        // Trades on other markets count: the rule is "for the next N trades", not "for the next N
        // trades on this market". Otherwise an idle market would be parked indefinitely.
        assertEquals(3, cooldown.state(vol25Differs2)?.tradesRemaining)
    }

    @Test
    fun `the entry becomes tradeable again once five trades have passed`() {
        val cooldown = tracker()
        cooldown.lose(vol25Differs2, 3)

        repeat(5) { cooldown.advancePastTrade() }

        assertNull(cooldown.state(vol25Differs2))
        assertTrue(cooldown.coolingOff.value.isEmpty())
    }

    @Test
    fun `a win clears both the tally and the cool-off`() {
        val cooldown = tracker()
        cooldown.lose(vol25Differs2, 3)
        cooldown.record(vol25Differs2, isWin = true)

        assertNull(cooldown.state(vol25Differs2))
        assertTrue(cooldown.coolingOff.value.isEmpty())
    }

    @Test
    fun `a win part-way through a losing run resets the count`() {
        val cooldown = tracker()
        cooldown.lose(vol25Differs2, 2)
        cooldown.record(vol25Differs2, isWin = true)
        cooldown.lose(vol25Differs2, 2)

        // Two + two is four losses in total, but never three in a row, so the entry is still free.
        assertFalse(cooldown.state(vol25Differs2)!!.isCoolingOff)
    }

    @Test
    fun `losing on one digit does not restrict another digit on the same market`() {
        val cooldown = tracker()
        cooldown.lose(vol25Differs2, 3)

        assertTrue(cooldown.state(TradeEntry("R_25", 7, TradeType.DIFFERS)) == null)
        assertEquals(setOf(2), cooldown.parkedDigits("R_25", TradeType.DIFFERS))
    }

    @Test
    fun `losing on one market does not restrict the same digit elsewhere`() {
        val cooldown = tracker()
        cooldown.lose(vol25Differs2, 3)

        assertNull(cooldown.state(TradeEntry("R_75", 2, TradeType.DIFFERS)))
        assertTrue(cooldown.parkedDigits("R_75", TradeType.DIFFERS).isEmpty())
    }

    @Test
    fun `a differs loss does not park the recovery contract on the same market and digit`() {
        val cooldown = tracker()
        cooldown.lose(vol25Differs2, 3)

        // `≠ 2` and `over 2` settle on the same digit but are unrelated bets. Parking recovery on a
        // Differs run would refuse the rung the martingale ladder is waiting on, stranding the
        // outstanding stake — the one outcome worse than re-taking a market that has been losing.
        assertNull(cooldown.state(TradeEntry("R_25", 2, TradeType.OVER_UNDER)))
        assertTrue(cooldown.parkedDigits("R_25", TradeType.OVER_UNDER).isEmpty())
    }

    @Test
    fun `repeated recovery losses park that recovery contract`() {
        val cooldown = tracker()
        val recovery = TradeEntry("R_25", 2, TradeType.OVER_UNDER)
        cooldown.lose(recovery, 3)

        assertEquals(listOf(recovery), cooldown.coolingOff.value)
    }

    @Test
    fun `after a cool-off expires the entry needs a fresh run of losses to be parked again`() {
        val cooldown = tracker()
        cooldown.lose(vol25Differs2, 3)
        repeat(5) { cooldown.advancePastTrade() }

        // Two more losses must not immediately re-park it, or one bad run would exclude the entry
        // from the rest of the session.
        cooldown.lose(vol25Differs2, 2)
        assertNull(cooldown.state(vol25Differs2)?.let { if (it.isCoolingOff) it else null })
    }

    @Test
    fun `two markets and two digits can be cooling off at the same time`() {
        val cooldown = tracker()
        val a = TradeEntry("R_25", 2, TradeType.DIFFERS)
        val b = TradeEntry("R_75", 6, TradeType.DIFFERS)
        cooldown.lose(a, 3)
        cooldown.lose(b, 3)
        cooldown.advancePastTrade()

        assertEquals(4, cooldown.state(a)?.tradesRemaining)
        assertEquals(4, cooldown.state(b)?.tradesRemaining)
        assertEquals(listOf(a, b).sortedBy { it.symbol }, cooldown.coolingOff.value)
    }

    @Test
    fun `clear forgets every entry`() {
        val cooldown = tracker()
        cooldown.lose(vol25Differs2, 3)
        cooldown.lose(TradeEntry("R_50", 1, TradeType.DIFFERS), 3)

        cooldown.clear()

        assertTrue(cooldown.coolingOff.value.isEmpty())
        assertNull(cooldown.state(vol25Differs2))
    }

    @Test
    fun `the loss that trips the threshold does not spend one of its own trades`() {
        // advancePastTrade() must be called before record() by the engine. This pins the ordering
        // consequence: the third loss must buy the full five trades, not four.
        val cooldown = tracker()
        cooldown.lose(vol25Differs2, 2)
        cooldown.advancePastTrade()
        cooldown.record(vol25Differs2, isWin = false)

        assertEquals(5, cooldown.state(vol25Differs2)?.tradesRemaining)
    }
}