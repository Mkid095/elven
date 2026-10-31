package com.tickedge.domain

import com.tickedge.data.model.SupportedMarkets
import com.tickedge.data.model.TradeType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * The exact contract an order would go into: a market, the digit that contract uses as its barrier,
 * and *which* of the two contract types that digit is being used as.
 *
 * The trade type is part of the identity on purpose. `≠ 2` on Vol 25 and `over 2` on Vol 25 settle on
 * the same last digit but are unrelated bets with unrelated probabilities, so a losing streak on one
 * is no evidence at all about the other. Keying on market+digit alone would let three Differs losses
 * park the Over/Under rung the martingale ladder is waiting on — and since recovery exists to chase
 * an outstanding stake, silently declining to place it would strand the money. That is the exact
 * failure the uncapped ladder was built to remove.
 */
data class TradeEntry(
    val symbol: String,
    val digit: Int,
    val tradeType: TradeType
) {
    /** Short human-readable form, for the reason line on the market card. */
    fun describe(): String {
        val market = SupportedMarkets.find(symbol).shortName
        return if (tradeType == TradeType.DIFFERS) {
            "$market ≠ $digit"
        } else {
            "$market recovery barrier $digit"
        }
    }
}

/** What the tracker knows about one entry: how it has done, and whether it is currently parked. */
data class CooldownState(
    /** Losses accumulated since the last win or cool-off. Not counted while cooling off. */
    val losses: Int = 0,
    /** Trades left to sit out. The entry becomes tradable again when this reaches zero. */
    val tradesRemaining: Int = 0
) {
    val isCoolingOff: Boolean get() = tradesRemaining > 0
}

/** Losses on one exact contract before the bot steps away from it. */
const val DEFAULT_LOSSES_BEFORE_COOL_OFF = 3

/** Trades that entry is then held out of, whatever else the bot does. */
const val DEFAULT_COOL_OFF_TRADES = 5

/**
 * Remembers which exact contracts are currently off-limits after repeated losses.
 *
 * Averages do not stop a bot from walking back into the same hole. Volatility 25 will qualify, lose,
 * qualify again and lose again — each individual reading looked reasonable, and only the sequence is
 * bad. This tracker sees the sequence: once an entry has lost [lossesBeforeCoolOff] times it is
 * dropped from consideration entirely for the next [coolOffTrades] trades, whatever its statistics
 * say in the meantime.
 *
 * Deliberately in-memory and deliberately *not* cleared between runs. A cool-off differs from the
 * martingale ladder state in the way that matters: it cannot strand exposure (it only ever refuses an
 * entry, never holds up a recovery the money depends on) and it decays on its own. Keeping it across
 * a Stop/Start is therefore safe, and it matches what the rule means — "this entry is on a break",
 * not "this entry was on a break for the last thirty seconds of the previous run".
 */
class MarketCooldownTracker(
    private val lossesBeforeCoolOff: Int = DEFAULT_LOSSES_BEFORE_COOL_OFF,
    private val coolOffTrades: Int = DEFAULT_COOL_OFF_TRADES
) {
    private val entries = ConcurrentHashMap<TradeEntry, CooldownState>()

    private val _coolingOff = MutableStateFlow<List<TradeEntry>>(emptyList())

    /** Entries currently being sat out, so the UI can say why nothing is being taken. */
    val coolingOff: StateFlow<List<TradeEntry>> = _coolingOff.asStateFlow()

    /** What is known about [entry]; null when nothing has been traded there. */
    fun state(entry: TradeEntry): CooldownState? = entries[entry]

    /** Digits currently parked on one market, so a random draw does not keep re-rolling them. */
    fun parkedDigits(symbol: String, tradeType: TradeType): Set<Int> =
        entries.entries
            .filter { it.key.symbol == symbol && it.key.tradeType == tradeType && it.value.isCoolingOff }
            .map { it.key.digit }
            .toSet()

    /**
     * Books one settled trade against its entry.
     *
     * A win clears both the tally and any cool-off: the entry has just paid, so the evidence for
     * sidelining it is gone. A loss raises the tally, and the tally reaching the limit opens a fresh
     * cool-off with the counter back at zero — so an entry that keeps losing has to earn a *new* run
     * of [lossesBeforeCoolOff] before being parked again, rather than being locked out permanently
     * by one bad run.
     */
    fun record(entry: TradeEntry, isWin: Boolean) {
        if (isWin) {
            if (entries.remove(entry) != null) publish()
            return
        }

        val previous = entries[entry] ?: CooldownState()
        val losses = previous.losses + 1
        entries[entry] = if (losses >= lossesBeforeCoolOff) {
            CooldownState(losses = 0, tradesRemaining = coolOffTrades)
        } else {
            CooldownState(losses = losses, tradesRemaining = previous.tradesRemaining)
        }
        publish()
    }

    /**
     * Ages every active cool-off by one trade.
     *
     * Called once per settled trade, whatever market it was on: the rule is "leave this entry alone
     * for the next N trades", not "for the next N trades on this market", so trading elsewhere still
     * burns the clock. Without that, an entry could sit out indefinitely on an idle market.
     *
     * Call this *before* [record]. A trade has to be able to open its own cool-off for the full
     * number of trades; decrementing afterwards would spend one of the trades the loss just bought
     * and let the entry back in one trade early.
     */
    fun advancePastTrade() {
        var changed = false
        for ((entry, state) in entries) {
            if (!state.isCoolingOff) continue
            changed = true
            if (state.tradesRemaining <= 1) entries.remove(entry)
            else entries[entry] = state.copy(tradesRemaining = state.tradesRemaining - 1)
        }
        if (changed) publish()
    }

    /** Forgets everything. Used by tests and by a full sign-out. */
    fun clear() {
        entries.clear()
        publish()
    }

    private fun publish() {
        _coolingOff.value = entries
            .filterValues { it.isCoolingOff }
            .keys
            .sortedWith(compareBy({ it.symbol }, { it.digit }))
    }
}