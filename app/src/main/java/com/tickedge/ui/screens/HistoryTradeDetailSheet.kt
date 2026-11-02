package com.tickedge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tickedge.data.local.SimulationPositionEntity
import com.tickedge.domain.RecoveryCalculator
import com.tickedge.domain.StrategyCatalog
import com.tickedge.ui.components.AccountChip
import com.tickedge.ui.theme.BorderDark
import com.tickedge.ui.theme.StatGreen
import com.tickedge.ui.theme.StatRed
import com.tickedge.ui.theme.SurfaceDarkCard
import com.tickedge.ui.theme.TextPrimary
import com.tickedge.ui.theme.TextSecondary
import com.tickedge.ui.theme.VividYellow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full detail for one trade.
 *
 * Every figure here is what was recorded **at the time of the trade**. The recovery snapshot in
 * particular is deliberately not recomputed from the current ladder: it says which rung this was and
 * what it was clearing, which is the only way a win makes sense next to the losses before it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TradeDetailSheet(
    trade: SimulationPositionEntity,
    onDismiss: () -> Unit
) {
    val stamp = remember { SimpleDateFormat("d MMM yyyy, HH:mm:ss", Locale.US) }
    val won = trade.outcome == "WON"
    val strategyLabel = if (trade.strategyId.isNotEmpty()) {
        StrategyCatalog.find(trade.strategyId).name
    } else {
        if (trade.scenarioType.contains("Even", ignoreCase = true)) "Even / Odd" else "Differs + Over/Under Recovery"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDarkCard,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column {
                Text(
                    text = trade.marketName.ifBlank { trade.marketSymbol },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                AccountChip(isReal = trade.isReal)
                Text(
                    text = when (trade.outcome) {
                        "WON" -> "Won"
                        "LOST" -> "Lost"
                        else -> trade.outcome
                    } + " · " + trade.scenarioType +
                        " · " + (if (trade.isReal) "Live account" else "Demo account"),
                    fontSize = 12.sp,
                    color = if (won) StatGreen else StatRed
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailTile(
                    label = "Profit / Loss",
                    value = String.format(
                        Locale.US, "%s$%.2f",
                        if (trade.simulatedNetResult >= 0) "+" else "",
                        trade.simulatedNetResult
                    ),
                    tint = if (trade.simulatedNetResult >= 0) StatGreen else StatRed,
                    modifier = Modifier.weight(1f)
                )
                DetailTile(
                    label = "Return rate",
                    value = trade.payoutRatio?.let {
                        String.format(Locale.US, "%.1f%%", it * 100.0)
                    } ?: "Unavailable",
                    tint = if (trade.payoutRatio != null) TextPrimary else TextSecondary,
                    modifier = Modifier.weight(1f)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                DetailRow("Strategy", strategyLabel)
                DetailRow(
                    "Risk",
                    when {
                        trade.strategyId.isNotEmpty() -> StrategyCatalog.find(trade.strategyId).risk.label
                        trade.scenarioType.contains("Even", ignoreCase = true) -> StrategyCatalog.EVEN_ODD.risk.label
                        else -> StrategyCatalog.DIFFERS_WITH_RECOVERY.risk.label
                    }
                )
                DetailRow(
                    "Prediction / barrier",
                    if (strategyLabel.startsWith("Even")) {
                        if (trade.targetDigit == 0) "Even class" else "Odd class"
                    } else {
                        "digit ${trade.targetDigit}"
                    }
                )
                DetailRow("Stake", String.format(Locale.US, "$%.2f", trade.simulatedStake))
                DetailRow(
                    "Price paid",
                    if (trade.askPrice > 0.0) String.format(Locale.US, "$%.2f", trade.askPrice) else "Unavailable"
                )
                DetailRow(
                    "Payout",
                    if (trade.simulatedPayout > 0.0) String.format(Locale.US, "$%.2f", trade.simulatedPayout) else "None"
                )
                DetailRow("Status", trade.outcome)
                DetailRow("Account", trade.accountId.ifBlank { "unknown" })
                DetailRow("Session", "#${trade.sessionId.takeIf { it != 0L } ?: "—"}")
                DetailRow("Opened", stamp.format(Date(trade.openedAt)))
                DetailRow("Settled", stamp.format(Date(trade.settledAt)))
                DetailRow("Contract id", trade.contractId.ifBlank { "Not recorded" }, monospace = true)
            }

            // Recovery context, only for a recovery rung.
            if (trade.recoveryStep > 0 || trade.outstandingBefore > 0.0) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF171A24), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = "Recovery context", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = VividYellow)
                    Text(text = "Ladder rung ${trade.recoveryStep}", fontSize = 12.sp, color = TextPrimary)
                    Text(
                        text = "Total lost before this rung: " +
                            String.format(Locale.US, "$%.2f", trade.outstandingBefore),
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    val ratio = trade.payoutRatio
                    val hole = trade.outstandingBefore
                    if (ratio != null && ratio > 0.0 && hole > 0.0) {
                        val needed = hole * (1.0 + RecoveryCalculator.RECOVERY_TARGET_RATIO) / ratio
                        val placed = trade.simulatedStake
                        val clears = placed >= needed
                        Text(
                            text = "Stake needed to clear it and bank the target: " +
                                String.format(Locale.US, "$%.2f", needed),
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                        Text(
                            text = if (clears) {
                                "Stake placed: " + String.format(Locale.US, "$%.2f", placed) + " — clears the loss"
                            } else {
                                "Stake placed: " + String.format(Locale.US, "$%.2f", placed) +
                                    " — short by " + String.format(Locale.US, "$%.2f", needed - placed)
                            },
                            fontSize = 12.sp,
                            color = if (clears) StatGreen else StatRed
                        )
                    } else {
                        Text(
                            text = "Payout ratio was not recorded for this trade, so what the rung " +
                                "needed to clear cannot be stated. It is not guessed from settings.",
                            fontSize = 11.sp,
                            color = TextSecondary.copy(alpha = 0.75f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun DetailTile(
    label: String,
    value: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF171A24), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Text(text = label, fontSize = 11.sp, color = TextSecondary)
        Text(text = value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = tint)
    }
}

@Composable
internal fun DetailRow(label: String, value: String, monospace: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(text = label, fontSize = 12.sp, color = TextSecondary, modifier = Modifier.weight(0.45f))
        Text(
            text = value,
            fontSize = 12.sp,
            color = TextPrimary,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.weight(0.55f)
        )
    }
}
