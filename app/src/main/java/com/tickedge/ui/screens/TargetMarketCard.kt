package com.tickedge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tickedge.data.model.SupportedMarkets
import com.tickedge.domain.MarketReading
import com.tickedge.domain.ScanState
import com.tickedge.ui.theme.BorderDark
import com.tickedge.ui.theme.StatGreen
import com.tickedge.ui.theme.SurfaceDarkCard
import com.tickedge.ui.theme.TextPrimary
import com.tickedge.ui.theme.TextSecondary
import com.tickedge.ui.theme.VividYellow
import java.util.Locale

/**
 * What the bot is aiming at, shown while it searches rather than only once it has fired.
 *
 * The engine evaluates **every** subscribed market each poll and places the order into whichever one
 * cleared the threshold, so the card shows all of them: the market that was picked is highlighted,
 * the ones that came close are listed with their shortfall, and the ones still short of data say so
 * instead of silently reading 0%. Showing a single market here would misrepresent a decision that
 * was made across five.
 *
 * Each row is the reading for that market's own contract — `P(differs from N)` while trading
 * Differs, `P(over/under N)` while recovering — so the percentages answer the question that market
 * is actually being traded on.
 */
@Composable
fun TargetMarketCard(
    scanState: ScanState,
    modifier: Modifier = Modifier
) {
    val sweep = scanState.lastSweep
    val readings = sweep.readings
    val candidate = sweep.candidate
    val threshold = scanState.thresholdPercent

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
        modifier = modifier.fillMaxWidth().testTag("target_market_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (scanState.isScanning) "Scanning all markets" else "Market sweep",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )
                    Text(
                        text = candidate
                            ?.let { SupportedMarkets.find(it.symbol).shortName }
                            ?: if (readings.isEmpty()) "—" else "${readings.size} markets checked",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
                StatusPill(scanState)
            }

            if (readings.isNotEmpty()) {
                SweepSummary(
                    evaluated = sweep.evaluatedCount,
                    qualifying = sweep.qualifyingCount,
                    threshold = threshold,
                    isGreaterThan = scanState.isGreaterThan,
                    modifier = Modifier.padding(top = 12.dp)
                )

                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    readings.forEach { reading ->
                        ReadingRow(
                            reading = reading,
                            threshold = threshold,
                            isGreaterThan = scanState.isGreaterThan,
                            isChosen = reading.symbol == candidate?.symbol
                        )
                    }
                }
            }

            if (scanState.reason.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .background(Color(0xFF171A24), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(text = scanState.reason, fontSize = 11.sp, color = TextSecondary)
                }
            }

            Text(
                text = "Percentages describe the current tick window, not a prediction of the " +
                    "next tick. The bot re-reads every market on each poll and enters only when a " +
                    "market meets the condition.",
                fontSize = 10.sp,
                color = TextSecondary.copy(alpha = 0.75f),
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

/** One line stating what the sweep just did, independent of any single row. */
@Composable
private fun SweepSummary(
    evaluated: Int,
    qualifying: Int,
    threshold: Double?,
    isGreaterThan: Boolean,
    modifier: Modifier = Modifier
) {
    val tint = if (qualifying > 0) StatGreen else TextSecondary
    Row(
        modifier = modifier.fillMaxWidth().background(Color(0xFF171A24), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (qualifying > 0)
                "$qualifying of $evaluated qualify" else
                "0 of $evaluated qualify",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = tint
        )
        if (threshold != null) {
            Text(
                text = "need ${if (isGreaterThan) ">" else "<"} " +
                    String.format(Locale.US, "%.1f%%", threshold),
                fontSize = 11.sp,
                color = TextSecondary
            )
        }
    }
}

/** One market in the sweep, with its reading and how far it sits from the threshold. */
@Composable
private fun ReadingRow(
    reading: MarketReading,
    threshold: Double?,
    isGreaterThan: Boolean,
    isChosen: Boolean
) {
    val marketName = SupportedMarkets.find(reading.symbol).shortName
    val border = if (isChosen) StatGreen else BorderDark
    val bg = if (isChosen) Color(0xFF0F2419) else Color(0xFF171A24)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(8.dp))
            .border(1.dp, border.copy(alpha = if (isChosen) 0.9f else 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("market_reading_${reading.symbol}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = marketName,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (isChosen) StatGreen else TextPrimary
            )
            Text(
                text = when {
                    reading.reason.isNotBlank() -> reading.reason
                    reading.parityLabel != null ->
                        "${reading.parityLabel}  ·  ${reading.sampleSize} ticks"
                    else -> "≠ ${reading.digit}  ·  ${reading.sampleSize} ticks"
                },
                fontSize = 10.sp,
                color = TextSecondary
            )
        }

        val observed = reading.observedPercent
        if (observed != null && threshold != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = String.format(Locale.US, "%.1f%%", observed),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (reading.qualifies) StatGreen else TextPrimary
                )
                ReadingBar(
                    observedPercent = observed,
                    thresholdPercent = threshold,
                    isGreaterThan = isGreaterThan,
                    qualified = reading.qualifies
                )
            }
        } else {
            Text("—", fontSize = 13.sp, color = TextSecondary)
        }
    }
}

/**
 * How far this market's reading sits between "nowhere near the threshold" and "past it".
 *
 * Computed against the metric's own 0-100 range so it means the same thing for a Differs reading
 * (which lives near 90%) and an Over/Under reading (which lives near 50%).
 */
@Composable
private fun ReadingBar(
    observedPercent: Double,
    thresholdPercent: Double,
    isGreaterThan: Boolean,
    qualified: Boolean
) {
    val progress = (observedPercent / thresholdPercent.coerceAtLeast(1.0))
        .coerceIn(0.0, 1.0).toFloat()

    Box(
        modifier = Modifier
            .padding(top = 4.dp)
            .fillMaxWidth(0.42f)
            .height(4.dp)
            .background(Color(0xFF232734), RoundedCornerShape(2.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceAtLeast(0.02f))
                .height(4.dp)
                .background(
                    when {
                        qualified -> StatGreen
                        isGreaterThan -> VividYellow
                        else -> Color(0xFF6B7280)
                    },
                    RoundedCornerShape(2.dp)
                )
        )
    }
}

@Composable
private fun StatusPill(scanState: ScanState) {
    val qualifying = scanState.lastSweep.qualifyingCount
    val cooling = scanState.lastSweep.coolingOffCount
    val (label, tint, bg) = when {
        // Held, not repeating: the only qualifying market is the one that just traded. The sweep
        // declines to repeat it, so the correct word is a wait — "Repeating" would describe the
        // behaviour this replaced.
        scanState.lastSweep.heldBack != null -> Triple("Holding", VividYellow, Color(0xFF232734))
        scanState.lastSweep.candidate != null -> Triple("Entering", StatGreen, Color(0xFF0F2419))
        // Markets are being skipped on purpose for having lost. "Searching" next to a row showing a
        // qualifying percentage would read as the maths being wrong.
        scanState.isScanning && cooling > 0 -> Triple("Cooling ($cooling)", VividYellow, Color(0xFF232734))
        scanState.isScanning && qualifying > 0 -> Triple("Armed", VividYellow, Color(0xFF232734))
        scanState.isScanning -> Triple("Searching", VividYellow, Color(0xFF232734))
        else -> Triple("Idle", TextSecondary, Color(0xFF232734))
    }
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(8.dp))
            .border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .testTag("scan_status_pill")
    ) {
        Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = tint)
    }
}