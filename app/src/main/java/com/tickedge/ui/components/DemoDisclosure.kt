package com.tickedge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import com.tickedge.ui.theme.BorderDark
import com.tickedge.ui.theme.StatGreen
import com.tickedge.ui.theme.StatRed
import com.tickedge.ui.theme.TextSecondary
import com.tickedge.ui.theme.VividYellow

/**
 * Colour and wording for results that did not involve real money.
 *
 * Deliberately loud. Demo and live results are the same shape, the same green-for-a-win and
 * red-for-a-loss, and the same dollar signs — so a screenshot of a profitable demo run is
 * indistinguishable from a profitable real one unless the app says otherwise. Anyone can take a
 * screenshot; the only defence available to the person taking it is that the image carries the
 * distinction itself, on every figure, with no way to crop it out of the number.
 *
 * Amber for demo, red for live. Red is not a warning here — it is the app's existing colour for
 * real money, and keeping it means a live figure is recognisable as live at a glance without
 * reading the badge. The two are deliberately opposite in temperature so they cannot be confused
 * for one another in a thumbnail.
 */
object DemoStyle {
    val demoTint: Color = VividYellow
    val liveTint: Color = StatRed
    val demoSurface: Color = Color(0xFF2C2812)
    val liveSurface: Color = Color(0xFF2A1418)

    fun tint(isReal: Boolean): Color = if (isReal) liveTint else demoTint

    fun surface(isReal: Boolean): Color = if (isReal) liveSurface else demoSurface

    /** Where a figure came from, in words that cannot be mistaken for one another. */
    fun label(isReal: Boolean): String = if (isReal) "LIVE" else "DEMO"

    fun qualifier(isReal: Boolean): String =
        if (isReal) "real money" else "no real money — this is a practice account"
}

/**
 * A full-width statement of what kind of account the numbers below came from.
 *
 * Placed at the top of any surface that reports P&L, so the distinction is above the figures rather
 * than wherever the reader happens to scroll to.
 */
@Composable
fun AccountRibbon(
    isReal: Boolean,
    /** Set when the surface mixes both, which is neither clean demo nor clean live. */
    isMixed: Boolean = false,
    modifier: Modifier = Modifier
) {
    val text = when {
        isMixed -> "MIXED ACCOUNTS — some rows below are demo, some are live"
        isReal -> "LIVE ACCOUNT — these results used real money"
        else -> "DEMO ACCOUNT — none of this used real money"
    }
    val live = isReal && !isMixed
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(DemoStyle.surface(live))
            .border(1.dp, DemoStyle.tint(live), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("account_ribbon")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = DemoStyle.label(live),
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                color = DemoStyle.tint(live)
            )
            Text(text = text, fontSize = 11.sp, color = TextSecondary)
        }
    }
}

/**
 * An inline DEMO/LIVE badge for a single row or tile.
 *
 * Used wherever one figure is shown on its own — a trade row, a session row, a detail sheet — so
 * that no individual number can be screenshotted or cropped away from its account type.
 */
@Composable
fun AccountChip(
    isReal: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(DemoStyle.surface(isReal))
            .border(1.dp, DemoStyle.tint(isReal), RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag("account_chip")
    ) {
        Text(
            text = DemoStyle.label(isReal),
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            color = DemoStyle.tint(isReal)
        )
    }
}

/**
 * A repeated diagonal watermark for surfaces showing aggregate P&L.
 *
 * The ribbon says what kind of account the figures are; this says it again *across* them, so a
 * cropped screenshot of the chart or the totals still carries the word. Deliberately low-contrast:
 * legible enough to survive a crop, faint enough that the numbers stay readable underneath.
 */
@Composable
fun DemoWatermark(
    isReal: Boolean,
    modifier: Modifier = Modifier,
    lines: Int = 3
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        repeat(lines) {
            Text(
                text = if (isReal) "LIVE — REAL MONEY" else "DEMO — NOT REAL MONEY",
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
                color = DemoStyle.tint(isReal).copy(alpha = 0.13f),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Border colour for a card whose contents are demo results. */
fun demoAwareBorder(isReal: Boolean, normal: Color = BorderDark): Color =
    DemoStyle.tint(isReal).copy(alpha = 0.45f)

/**
 * Header for one account's block of results.
 *
 * Used where both accounts are on screen at once, with the live block placed above the demo block.
 * Keeping them apart rather than interleaved matters for more than tidiness: a demo profit and a
 * live profit sitting in one undifferentiated list can be read as one total, and that is exactly
 * the misreading this whole treatment exists to prevent.
 */
@Composable
fun AccountSectionHeader(
    isReal: Boolean,
    tradeCount: Int,
    netPnl: Double,
    /** Marks the block for the account currently selected, when only one account is in use. */
    isCurrentAccount: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(DemoStyle.surface(isReal))
            .border(1.dp, DemoStyle.tint(isReal), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .testTag("account_section_${DemoStyle.label(isReal).lowercase()}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = DemoStyle.label(isReal),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    color = DemoStyle.tint(isReal)
                )
                Text(
                    text = if (isReal) "real money" else "practice account",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
                if (isCurrentAccount) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF232734), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "in use",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$tradeCount trade" + if (tradeCount == 1) "" else "s",
                    fontSize = 10.sp,
                    color = TextSecondary
                )
                Text(
                    text = String.format(Locale.US, "%s$%.2f", if (netPnl >= 0) "+" else "", netPnl),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (netPnl >= 0) StatGreen else StatRed
                )
            }
        }
    }
}
