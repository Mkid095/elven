package com.tickedge.domain

import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What a strategy's run is doing at this moment.
 *
 * The states are the ones a user can act on: is it looking for a trade, is it holding one, is it
 * climbing back out of a hole, or has it stopped.
 */
enum class RunStatus {
    /** Never started, or reset. */
    IDLE,

    /** Running, looking for a market that qualifies. */
    SCANNING,

    /** Running, with a contract open. */
    IN_TRADE,

    /** Running, climbing the recovery ladder after a loss. */
    RECOVERING,

    /** Finished — by the user's Stop, or by a risk limit. */
    STOPPED;

    /** Whether the strategy is still doing something. */
    val isRunning: Boolean
        get() = this == SCANNING || this == IN_TRADE || this == RECOVERING

    /** A short word for the notification and the status bar. */
    val label: String
        get() = when (this) {
            IDLE -> "Idle"
            SCANNING -> "Scanning"
            IN_TRADE -> "In a trade"
            RECOVERING -> "Recovering"
            STOPPED -> "Stopped"
        }
}

/**
 * A strategy's live figures, as the notification and the strategy card show them.
 *
 * Deliberately separate from the engine's own state. The engine knows about one run; this is the
 * published shape of a run, so a notification can describe several strategies at once without
 * knowing anything about how any of them work.
 */
data class StrategyRunState(
    val strategyId: String,
    val strategyName: String,
    val status: RunStatus = RunStatus.IDLE,
    /** Trades settled in this run, wins and losses together. */
    val trades: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    /** Realised profit or loss for this run. */
    val netPnl: Double = 0.0,
    /** Losses in the current streak — the number a recovery ladder is climbing out of. */
    val consecutiveLosses: Int = 0,
    /** What the bot is doing right now, in one line. Null when it is not in a trade. */
    val activeTradeSummary: String? = null,
    val startedAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    /** Whether this strategy is still doing something. */
    val isRunning: Boolean get() = status.isRunning

    /** The run's profit or loss, signed and formatted for display. */
    val pnlLabel: String
        get() = (if (netPnl >= 0) "+" else "-") + "$" +
            String.format(Locale.US, "%.2f", abs(netPnl))

    /** "7 trades" / "1 trade". */
    val tradesLabel: String get() = "$trades ${if (trades == 1) "trade" else "trades"}"

    /** "2 wins · 1 loss", omitted when nothing has settled yet. */
    val recordLabel: String?
        get() = if (trades == 0) null else "$wins win${if (wins == 1) "" else "s"} · " +
            "$losses loss${if (losses == 1) "" else "es"}"

    /**
     * One line describing the run, for the collapsed notification.
     *
     * The most urgent thing about a run is what it is doing right now, so the active trade leads;
     * the figures follow.
     */
    fun headline(): String {
        val figures = "$tradesLabel · $pnlLabel"
        return activeTradeSummary ?: "${status.label} · $figures"
    }
}

/**
 * The live state of every strategy that has run in this session.
 *
 * Held by the runtime rather than the ViewModel, because a notification has to survive the
 * Activity — a registry that lived in a ViewModel would be empty the moment the app was
 * backgrounded, which is exactly when the notification matters most.
 */
class StrategyRunRegistry {

    private val _states = MutableStateFlow<Map<String, StrategyRunState>>(emptyMap())

    /** Every known strategy, keyed by strategy id. */
    val states: StateFlow<Map<String, StrategyRunState>> = _states.asStateFlow()

    /**
     * Records a strategy's current state.
     *
     * The whole state is published rather than a patch, because the engine owns the truth about
     * its own run. Copying it wholesale is what stops the notification and the engine from
     * describing the same run differently.
     */
    fun put(state: StrategyRunState) {
        _states.update { current ->
            current + (state.strategyId to state.copy(updatedAt = System.currentTimeMillis()))
        }
    }
}
