package com.tickedge.domain

/**
 * Spreads orders across the market field by how recently each one has been traded.
 *
 * The field is small — five markets — and every one of them can qualify on any given sweep. Picking
 * uniformly at random among qualifiers sounds fair and is not: the markets are not equally likely
 * to qualify, so the busiest ones get picked far more often, and a short run drifts into the same
 * two or three markets over and over while the rest are never looked at again. Nothing recorded
 * that, so nothing could correct it.
 *
 * This is the cool-off's mirror image. The cool-off steps away from a market that has been *losing*;
 * this steps away from a market that has been traded *too often*. A market that has carried its
 * [maxTradesPerMarket] trades inside the recent window is set aside until the others have caught
 * up, which is what turns a random walk into a rotation.
 *
 * In-memory and deliberately not cleared between runs, for the same reason the cool-off is not: how
 * often the bot has been leaning on one market is a fact about the bot's behaviour, not about one
 * session, and a Stop/Start should not hand it a clean slate.
 */
class MarketRotation(
    private val maxTradesPerMarket: Int = DEFAULT_MAX_TRADES_PER_MARKET,
    private val windowTrades: Int = DEFAULT_ROTATION_WINDOW_TRADES,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /**
     * The most recent trades, oldest first, capped at [windowTrades].
     *
     * A window rather than all-time totals on purpose. What matters is how often a market has been
     * used *lately*: a market traded twice this minute and then untouched for an hour is not a
     * problem, while one traded twice in the last five trades is exactly the concentration this
     * exists to break up.
     */
    private val recent = ArrayDeque<String>()
    private val lastTradedAt = mutableMapOf<String, Long>()

    /** Books one trade against the field. Called once per settled trade. */
    fun record(symbol: String) {
        recent.addLast(symbol)
        while (recent.size > windowTrades) recent.removeFirst()
        lastTradedAt[symbol] = clock()
    }

    /** How many of the recent window's trades this market carried. */
    fun tradesInWindow(symbol: String): Int = recent.count { it == symbol }

    /**
     * Whether this market has already carried its share of the recent window.
     *
     * At most [maxTradesPerMarket] is allowed while any other market has carried fewer. This is a
     * ceiling, not a ban: the caller falls back to the over-frequency market when every qualifier
     * is over, because a missing trade is worse than a repeated one.
     */
    fun isOverFrequency(symbol: String): Boolean =
        maxTradesPerMarket > 0 && tradesInWindow(symbol) >= maxTradesPerMarket

    /** When this market was last traded, or null if it has not been traded at all. */
    fun lastTraded(symbol: String): Long? = lastTradedAt[symbol]

    /** Forgets everything. Used by tests and by a full sign-out. */
    fun clear() {
        recent.clear()
        lastTradedAt.clear()
    }
}

/** Trades one market may carry inside the rotation window before the others catch up. */
const val DEFAULT_MAX_TRADES_PER_MARKET = 3

/**
 * How many recent trades the window spans.
 *
 * Twice the per-market ceiling over a five-market field, so a market has to fall out of the window
 * — or the window has to fill with other markets — before it is considered free again. A window
 * shorter than the field would let every market be "over frequency" at once and the ceiling would
 * mean nothing.
 */
const val DEFAULT_ROTATION_WINDOW_TRADES = 12
