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
 * Replaces the dead air where a scanning bot showed nothing at all: before, an engine that was
 * polling markets and finding no qualifying entry looked exactly like one that had stopped. This
 * names the market under evaluation, the digit it drew (in random mode a *different* digit each
 * poll), how far the live reading is from the threshold, and — when nothing can be evaluated yet —
 * why.
 *
 * The progress bar shows where the observed Differs % sits between "nowhere near the threshold"
 * and "past it", which is the only honest way to present a live measurement against a target.
 */
@Composable
fun TargetMarketCard(
    scanState: ScanState,
    modifier: Modifier = Modifier
) {
    val symbol = scanState.symbol
    val marketName = symbol?.let { SupportedMarkets.find(it).shortName } ?: "—"
    val observed = scanState.observedPercent
    val threshold = scanState.thresholdPercent

    // Progress toward the threshold: 0 when far below the target, 1 when met. Computed against the
    // metric's own 0-100 range so it means the same thing whichever direction the user is trading.
    val progress = when {
        observed == null || threshold == null -> 0f
        else -> (observed / threshold.coerceAtLeast(1.0)).coerceIn(0.0, 1.0).toFloat()
    }

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
                        text = if (scanState.isScanning) "Scanning for entry" else "Target market",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )
                    Text(
                        text = marketName,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
                StatusPill(scanState)
            }

            if (scanState.targetDigit != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Chip("Differs from digit", scanState.targetDigit.toString())
                    Chip("Sample", "${scanState.sampleSize} ticks")
                }
            }

            if (observed != null && threshold != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .height(8.dp)
                        .background(Color(0xFF232734), RoundedCornerShape(4.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                            .height(8.dp)
                            .background(
                                if (scanState.qualified) StatGreen else VividYellow,
                                RoundedCornerShape(4.dp)
                            )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Differs ${String.format(Locale.US, "%.1f%%", observed)}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (scanState.qualified) StatGreen else TextPrimary
                    )
                    Text(
                        text = "need ${if (scanState.isGreaterThan) ">" else "<"} " +
                            String.format(Locale.US, "%.1f%%", threshold),
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
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
                    "next tick. The bot re-reads them on every poll and enters only when the " +
                    "condition is met.",
                fontSize = 10.sp,
                color = TextSecondary.copy(alpha = 0.75f),
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

@Composable
private fun StatusPill(scanState: ScanState) {
    val (label, tint, bg) = when {
        scanState.qualified -> Triple("Qualified", StatGreen, Color(0xFF0F2419))
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

@Composable
private fun Chip(label: String, value: String) {
    Row(
        modifier = Modifier
            .background(Color(0xFF232734), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text = label, fontSize = 11.sp, color = TextSecondary)
        Text(text = value, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = VividYellow)
    }
}
