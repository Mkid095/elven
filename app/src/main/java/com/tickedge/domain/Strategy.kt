package com.tickedge.domain

/**
 * A strategy the user can run.
 *
 * The app was built around exactly one way of trading, so "the strategy" and "the app" were the
 * same thing: the bottom bar was hard-coded, the Trade screen knew one entry rule, and adding a
 * second way to trade would have meant a second app. Splitting them out means a strategy is a
 * choice the user makes on the way in, and each one carries its own menu — so a strategy that needs
 * no Analysis tab does not have to show one, and a strategy that needs four tabs it does not have
 * can have them.
 *
 * Only the shape lives here. The *behaviour* of a strategy — which entry rule it uses, what its
 * screens show — is reached through its own routes, so the catalogue does not have to know about
 * the screens it leads to.
 */
data class StrategyDefinition(
    /** Stable id, persisted so the app can reopen on the strategy the user last chose. */
    val id: String,
    /** Name shown in the strategy list. */
    val name: String,
    /** One line describing the approach, for the picker. */
    val tagline: String,
    /** A couple of sentences, for the picker, saying what it does and what it risks. */
    val description: String,
    /**
     * The menu this strategy shows.
     *
     * Each strategy's own list, in its own order. The routes are the same ones the screens use, so
     * a strategy that reuses the History screen gets it for free.
     */
    val navItems: List<StrategyNavItem>,
    /** Where the app lands when this strategy is chosen. */
    val defaultRoute: String,
    /**
     * Whether this strategy trades real money the way the live-account switcher does.
     *
     * True for the built-in strategy. A future strategy that is analysis-only — a scanner, say —
     * sets it false and never shows the account switcher, because there is nothing to switch.
     */
    val usesTradingAccount: Boolean = true,
    /**
     * The plain-English explanation of this strategy.
     *
     * Part of the definition rather than a separate document, so a strategy cannot ship without one.
     */
    val guide: StrategyGuide,
    /**
     * The risk the strategy carries, stated plainly.
     *
     * A tier ("Low risk" / "High risk") and an approximate win rate, so the numbers sit next to
     * every trade the strategy places rather than staying in a user's head. Part of the
     * definition like [guide], so a strategy cannot ship without saying what it risks.
     */
    val risk: StrategyRisk
)

/**
 * A strategy's risk, in words a trader understands.
 *
 * The win rate is deliberately approximate — the `~` says the number is a long-run tendency of
 * the market the strategy plays, not a guarantee, and the figures here are the ones the user
 * accepted when choosing which risks to show.
 */
data class StrategyRisk(
    /** The tier, shown alone where there is no room for a full line. */
    val tier: String,
    /** The approximate win rate, shown next to the tier. */
    val winRate: String
) {
    /** "Low risk · ~83% win rate" — the full line, for surfaces with room. */
    val label: String get() = "$tier · $winRate"
}

/** One entry in a strategy's menu. */
data class StrategyNavItem(
    val route: String,
    val label: String
)

/**
 * The strategies the app offers.
 *
 * A list rather than an enum, so a strategy can be added without touching the code that switches
 * on strategy ids. The ids are stable and persisted, so an entry here can never be renamed without
 * stranding a returning user on a strategy that no longer exists — [find] falls back to the first
 * strategy rather than failing.
 */
object StrategyCatalog {

    /**
     * The first strategy: enter on "differs from digit", recover losses on Over/Under.
     *
     * The two contract types are genuinely different bets with different probabilities, which is why
     * the entry condition and the recovery condition are separate thresholds rather than one.
     */
    val DIFFERS_WITH_RECOVERY = StrategyDefinition(
        id = "differs_with_recovery",
        name = "Differs + Over/Under Recovery",
        tagline = "Enter on ≠ digit, recover losses on Over/Under",
        description = "Opens each run with a Differs contract — betting the last digit will not " +
            "match — and switches to an Over/Under contract to recover when a run goes against " +
            "it. The recovery contract is sized from the venue's own payout quote, so each rung " +
            "is priced for the loss it is clearing.",
        navItems = listOf(
            StrategyNavItem("home", "Home"),
            StrategyNavItem("analysis", "Analysis"),
            StrategyNavItem("trade", "Trade"),
            StrategyNavItem("history", "History"),
            StrategyNavItem("settings", "Settings")
        ),
        defaultRoute = "home",
        guide = StrategyGuides.DIFFERS_WITH_RECOVERY,
        risk = StrategyRisk("Low risk", "~83% win rate")
    )

    /**
     * The Even/Odd strategy: enter and recover on the **parity** of the last digit.
     *
     * The engine reads the same tick window every other strategy reads and sums the last digits
     * that are even ({0,2,4,6,8}) against the ones that are odd ({1,3,5,7,9}). The trade is a
     * DIGITEVEN or DIGITODD contract — no digit barrier, the class itself is the bet — and the
     * recovery rungs are parity contracts too, sized from the venue's own quote. Everything the
     * other strategies carry (rotation, cool-off, no-repeat, stop loss, daily limit, balance
     * guard, sessions) applies here without change.
     */
    val EVEN_ODD = StrategyDefinition(
        id = "even_odd",
        name = "Even / Odd",
        tagline = "Enter on even vs. odd, recover on even vs. odd",
        description = "Watches the last digit of each tick across all five Volatility markets " +
            "and counts how many of them are even against how many are odd. When one side " +
            "clears your percentage, the bot places an Even or Odd contract on that market — " +
            "and when one loses, the recovery rung is also an Even/Odd contract, sized from " +
            "the venue's own quote.",
        navItems = listOf(
            StrategyNavItem("home", "Home"),
            StrategyNavItem("analysis", "Analysis"),
            StrategyNavItem("trade", "Trade"),
            StrategyNavItem("history", "History"),
            StrategyNavItem("settings", "Settings")
        ),
        defaultRoute = "home",
        guide = StrategyGuides.EVEN_ODD,
        risk = StrategyRisk("High risk", "~50% win rate")
    )

    /** Every strategy the app offers, in the order the picker lists them. */
    val ALL: List<StrategyDefinition> = listOf(DIFFERS_WITH_RECOVERY, EVEN_ODD)

    /** The strategy used when none has been chosen yet. */
    val DEFAULT: StrategyDefinition = DIFFERS_WITH_RECOVERY

    /** Looks a strategy up by id, falling back to [DEFAULT] rather than failing. */
    fun find(id: String?): StrategyDefinition =
        ALL.firstOrNull { it.id == id } ?: DEFAULT

    /**
     * The strategy a menu route belongs to.
     *
     * Used when the app is resumed on a route that the newly selected strategy does not offer —
     * picking a different strategy while sitting on a screen it does not have must not leave the
     * app showing a screen from the previous one.
     */
    fun ownsRoute(strategy: StrategyDefinition, route: String): Boolean =
        strategy.navItems.any { it.route == route }
}
