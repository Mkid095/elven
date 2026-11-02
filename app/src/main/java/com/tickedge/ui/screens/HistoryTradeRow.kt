package com.tickedge.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tickedge.data.local.SimulationPositionEntity
import com.tickedge.domain.StrategyCatalog
import com.tickedge.ui.components.AccountChip
import com.tickedge.ui.components.DemoStyle
import com.tickedge.ui.theme.StatGreen
import com.tickedge.ui.theme.StatRed
import com.tickedge.ui.theme.SurfaceDarkCard
import com.tickedge.ui.theme.TextPrimary
import com.tickedge.ui.theme.TextSecondary
import com.tickedge.ui.theme.VividYellow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The display strings for one trade row, computed once per row and cached on the entity. */
private data class TradeRowView(
    val pos: SimulationPositionEntity,
    val timeText: String,
    val typeText: String,
    val betText: String,
    val pnlText: String,
    val riskTier: String
)

/** Which strategy placed a trade, from the row's own stamp (else the scenario type). */
private fun strategyOf(pos: SimulationPositionEntity): String =
    when {
        pos.strategyId.isNotEmpty() -> pos.strategyId
        pos.scenarioType.contains("Even", ignoreCase = true) -> "even_odd"
        else -> StrategyCatalog.DEFAULT.id
    }

/** The contract-type label for the Type column: Differs, O/U, or Even/Odd by side. */
private fun typeLabel(pos: SimulationPositionEntity): String = when (strategyOf(pos)) {
    "even_odd" -> if (pos.targetDigit == 0) "E/O·Even" else "E/O·Odd"
    else -> if (pos.scenarioType.contains("Differs")) "Differs" else "O/U"
}

/** What the trade actually bet: the digit for Differs, the parity class for Even/Odd. */
private fun betLabel(pos: SimulationPositionEntity): String = when (strategyOf(pos)) {
    "even_odd" -> if (pos.targetDigit == 0) "Even" else "Odd"
    else -> if (pos.targetDigit >= 0) pos.targetDigit.toString() else "—"
}

/**
 * One trade row, recycled across the Trades tab and the session detail sheet.
 *
 * The display strings are built once per row and cached on the entity, so recomposition on a
 * settle does not re-run the date/label work for every visible row.
 */
@Composable
internal fun TradeRow(
    pos: SimulationPositionEntity,
    timeFormatter: SimpleDateFormat,
    onSelect: (SimulationPositionEntity) -> Unit
) {
    val row = remember(pos) {
        TradeRowView(
            pos = pos,
            timeText = timeFormatter.format(Date(pos.settledAt)),
            typeText = typeLabel(pos),
            betText = betLabel(pos),
            pnlText = String.format(
                Locale.US,
                "%s$%.2f",
                if (pos.outcome == "WON") "+" else "",
                pos.simulatedNetResult
            ),
            riskTier = StrategyCatalog.find(strategyOf(pos)).risk.tier
        )
    }
    val isWin = row.pos.outcome == "WON"
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, DemoStyle.tint(pos.isReal).copy(alpha = 0.30f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = { onSelect(row.pos) })
            .testTag("trade_row_${row.pos.contractId}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(row.timeText, fontSize = 12.sp, color = TextSecondary, modifier = Modifier.width(42.dp))
            Text(
                row.pos.marketName,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                modifier = Modifier.width(56.dp)
            )
            Text(row.typeText, fontSize = 12.sp, color = TextSecondary, modifier = Modifier.width(52.dp))
            Text(
                row.betText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = VividYellow,
                modifier = Modifier.width(38.dp)
            )
            Column(horizontalAlignment = Alignment.End) {
                AccountChip(isReal = row.pos.isReal)
                Text(
                    row.pnlText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isWin) StatGreen else StatRed
                )
                Text(row.riskTier, fontSize = 10.sp, color = TextSecondary)
            }
        }
    }
}
