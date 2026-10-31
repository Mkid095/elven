package com.tickedge.domain

/**
 * The plain-English explanation of a strategy.
 *
 * This exists because the alternative is a beginner reading "differs", "martingale" and
 * "over/under recovery" and understanding none of it. Every term the app uses is explained here in
 * a sentence, and the explanation is part of the strategy definition rather than a paragraph
 * written somewhere else — so a new strategy cannot ship without one.
 */
data class StrategyGuide(
    /** One sentence: what this strategy actually does. */
    val summary: String,
    /** The short version of how a decision is made, in order. */
    val howItWorks: List<String>,
    /** The words the app uses, and what each one means. */
    val words: List<GuideWord>,
    /** What the user is exposed to. Said plainly, without softening it. */
    val riskNote: String
)

/** One term and its meaning. */
data class GuideWord(
    val term: String,
    val meaning: String
)

/**
 * The guides, one per strategy.
 *
 * Kept beside the strategies themselves so the two cannot drift apart.
 */
object StrategyGuides {

    val EVEN_ODD = StrategyGuide(
        summary = "It watches each market's last digit and counts how often it lands " +
            "even (0, 2, 4, 6, 8) against odd (1, 3, 5, 7, 9), then bets on the side that " +
            "is showing up more.",
        howItWorks = listOf(
            "The bot counts the last digit of each of the last few ticks in every market " +
                "and adds up the evens and the odds. One side will usually lead.",
            "You set a percentage. When the leading side is above it (or, if you choose " +
                "\"below\", when a side is sitting below it), that market is an opportunity.",
            "It places an Even or Odd contract on that market: Even pays when the next " +
                "tick's last digit is 0, 2, 4, 6 or 8; Odd pays when it is 1, 3, 5, 7 or 9. " +
                "In Auto mode the side it bets is the one the numbers point at; in Even " +
                "or Odd mode you pin the side and the bot waits for it.",
            "If the bet wins, it takes the profit and starts over.",
            "If it loses, the recovery rung is also an Even/Odd contract — the side the " +
                "numbers point at again — sized from the venue's own quote so a win wins " +
                "back the loss and banks a little on top."
        ),
        words = listOf(
            GuideWord(
                "Even / Odd",
                "The last digit of the next tick: Even pays on 0, 2, 4, 6, 8 and Odd pays " +
                    "on 1, 3, 5, 7, 9. A coin flip, more or less — 50% each."
            ),
            GuideWord(
                "Auto mode",
                "The bot picks the side itself each trade: whichever of Even or Odd is " +
                    "running hotter over your window (or colder, when the condition is " +
                    "\"below\")."
            ),
            GuideWord(
                "Threshold",
                "The percentage a side has to clear before the bot bets on it. A side that " +
                    "has landed 60% of the last 15 ticks clears a 55% threshold."
            ),
            GuideWord(
                "Recovery",
                "After a loss, a bigger Even/Odd bet sized to win back the loss and bank a " +
                    "little profit on top. The recovery side is the one the numbers point " +
                    "at, not the one that just lost."
            ),
            GuideWord(
                "Cool-off",
                "After losing several times on the same market and side, that pair is set " +
                    "aside for a few trades so the bot tries somewhere else."
            ),
            GuideWord(
                "Market rotation",
                "The bot spreads its trades across the markets you selected, so it does " +
                    "not keep hammering the same one."
            )
        ),
        riskNote = "Even and Odd each win about half the time, so every trade here is a " +
            "coin flip at the venue's price — and the recovery bets are bigger coin flips " +
            "still. A losing streak costs more the longer it lasts, and the stop loss is " +
            "what limits that. Set it to an amount you are willing to lose in one run. On " +
            "a live account this is real money."
    )

    val DIFFERS_WITH_RECOVERY = StrategyGuide(
        summary = "It waits for a market where one digit keeps showing up less often " +
            "than the others, then bets that this digit will NOT come up on the next tick.",
        howItWorks = listOf(
            "The bot watches every market you have selected and counts how often each digit " +
                "appears.",
            "When one digit is showing up far less often than the others — you decide how " +
                "far with the Threshold setting — that market is an opportunity.",
            "It places a small \"Differs\" bet: that this unusual digit will not be the " +
                "next one to appear.",
            "If the bet wins, it takes the profit and starts over.",
            "If the bet loses, it does not stop. It switches to a bigger Over/Under bet, " +
                "sized to win back what was lost and bank a little profit on top."
        ),
        words = listOf(
            GuideWord(
                "Differs",
                "Bet that the next tick will not be a particular digit."
            ),
            GuideWord(
                "Over / Under",
                "Bet that the next tick's last digit will be above or below a number."
            ),
            GuideWord(
                "Recovery",
                "After a loss, a larger bet sized to win back the loss and still make a profit."
            ),
            GuideWord(
                "Martingale",
                "The rule for how much each recovery bet grows. Turn it off and the bot " +
                    "stays at the same stake after a loss."
            ),
            GuideWord(
                "Threshold",
                "How unusual a digit has to be before the bot bets. A higher number means " +
                    "more selective, and fewer trades."
            ),
            GuideWord(
                "Stop loss",
                "How much this run may lose in a row before the bot stops. A win that " +
                    "recovers the loss resets it."
            ),
            GuideWord(
                "Take profit",
                "How much this run should make before the bot stops and banks it."
            ),
            GuideWord(
                "Cool-off",
                "After losing several times on the same market, that market is set aside " +
                    "for a few trades so the bot tries somewhere else."
            ),
            GuideWord(
                "Market rotation",
                "The bot spreads its trades across the markets you selected, so it does " +
                    "not keep hammering the same one."
            )
        ),
        riskNote = "Recovery bets are larger than the first bet. A long losing streak " +
            "costs more than a short one, and the stop loss is what limits that — so set it " +
            "to an amount you are willing to lose in one run. On a live account this is " +
            "real money."
    )
}
