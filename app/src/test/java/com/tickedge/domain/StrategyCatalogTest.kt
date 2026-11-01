package com.tickedge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The strategy catalogue and the menu each strategy carries.
 *
 * The app was built around one way of trading, so these tests guard the thing that made that
 * possible to change: a strategy owns its own menu, and the app asks the catalogue rather than
 * assuming a fixed set of screens.
 */
class StrategyCatalogTest {

    @Test
    fun `the built in strategy is offered`() {
        assertTrue(StrategyCatalog.ALL.isNotEmpty())
        assertNotNull(StrategyCatalog.find(StrategyCatalog.DIFFERS_WITH_RECOVERY.id))
    }

    @Test
    fun `the differs and recovery strategy has the expected menu`() {
        val strategy = StrategyCatalog.DIFFERS_WITH_RECOVERY
        assertEquals(
            listOf("Home", "Analysis", "Trade", "History", "Settings"),
            strategy.navItems.map { it.label }
        )
        assertEquals("home", strategy.defaultRoute)
    }

    @Test
    fun `an unknown strategy id falls back to the default rather than failing`() {
        // A strategy that was renamed or removed must not strand a returning user on a screen
        // that does not exist.
        val fallback = StrategyCatalog.find("a_strategy_that_no_longer_exists")
        assertEquals(StrategyCatalog.DEFAULT.id, fallback.id)
        assertEquals(StrategyCatalog.find(null).id, fallback.id)
    }

    @Test
    fun `a strategy owns only the routes it lists`() {
        val strategy = StrategyCatalog.DIFFERS_WITH_RECOVERY
        assertTrue(StrategyCatalog.ownsRoute(strategy, "home"))
        assertTrue(StrategyCatalog.ownsRoute(strategy, "trade"))
        assertFalse(StrategyCatalog.ownsRoute(strategy, "a_screen_it_does_not_have"))
    }

    @Test
    fun `every strategy's default route is one of its own menu items`() {
        // A strategy whose landing screen is not in its own menu would show a screen the user
        // cannot navigate away from with the bar.
        StrategyCatalog.ALL.forEach { strategy ->
            assertTrue(
                "${strategy.id} lands on ${strategy.defaultRoute}, which it does not list",
                StrategyCatalog.ownsRoute(strategy, strategy.defaultRoute)
            )
        }
    }

    @Test
    fun `strategy ids are unique`() {
        // Ids are persisted, so a duplicate would make the stored choice ambiguous.
        val ids = StrategyCatalog.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `nav routes are unique within a strategy`() {
        StrategyCatalog.ALL.forEach { strategy ->
            val routes = strategy.navItems.map { it.route }
            assertEquals(
                "${strategy.id} lists a route twice",
                routes.size,
                routes.toSet().size
            )
        }
    }

    @Test
    fun `every strategy states a risk label that is not blank`() {
        // A risk label that is empty or a blank word is the same as no label: the user would be
        // trading blind to the strategy's risk. Guard the shape, not the exact figures.
        StrategyCatalog.ALL.forEach { strategy ->
            assertTrue("${strategy.id} has a blank risk tier", strategy.risk.tier.isNotBlank())
            assertTrue("${strategy.id} has a blank win rate", strategy.risk.winRate.isNotBlank())
            assertTrue("${strategy.id} has a blank risk label", strategy.risk.label.isNotBlank())
        }
    }

    @Test
    fun `the two strategies' risk tiers differ`() {
        // If both read the same the label carries no information: a trader cannot tell which
        // strategy is the riskier one. The figures the user approved are deliberately different.
        val tiers = StrategyCatalog.ALL.map { it.risk.tier }.toSet()
        assertEquals("the two strategies should not share a risk tier", 2, tiers.size)
    }
}
