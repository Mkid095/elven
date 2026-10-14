package com.tickedge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tickedge.data.local.LastTradeRow
import com.tickedge.data.local.SessionSummary
import com.tickedge.data.local.SimulationSessionEntity
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
 * Session results header for the History screen.
 *
 * Every figure here is derived from **confirmed** contract outcomes — `SessionSummary` filters on
 * `outcome IN ('WON','LOST')`, so an unsettled contract contributes neither to the trade count nor
 * a misleading 0.0 to the P&L. Requested stake and expected payout are deliberately not used.
 *
 * Shows: last confirmed trade, total realised P&L, completed trade count, session start and
 * status, and the account and strategy the session ran on.
 */
@Composable
fun SessionResultsCard(
    session: SimulationSessionEntity?,
    summary: SessionSummary?,
    lastTrade: LastTradeRow?,
    modifier: Modifier = Modifier
) {
    val startedFormat = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US) }
    val tradeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
        modifier = modifier.fillMaxWidth().testTag("session_results_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (session != null) "Session #${session.id}" else "No session yet",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = if (session != null) {
                            "Started ${startedFormat.format(Date(session.startedAt))}"
                        } else {
                            "Press Start Automated Trading to open one."
                        },
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
                SessionStatusPill(session?.status)
            }

            if (session != null) {
                Spacer8()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetaChip(
                        label = "Account",
                        value = session.accountId.ifBlank { "unknown" },
                        emphasis = session.isReal
                    )
                    MetaChip(label = "Strategy", value = session.strategy, emphasis = false)
                }
            }

            Spacer8()

            val realized = summary?.totalRealizedPnl ?: 0.0
            val wins = summary?.winCount ?: 0
            val losses = summary?.lossCount ?: 0
            val completed = summary?.completedTrades ?: 0

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricTile(
                    label = "Session P&L",
                    value = String.format(Locale.US, "%+.2f", realized),
                    valueColor = if (realized >= 0) StatGreen else StatRed,
                    modifier = Modifier.weight(1f),
                    testTag = "session_total_pnl"
                )
                MetricTile(
                    label = "Trades",
                    value = "$completed",
                    valueColor = TextPrimary,
                    modifier = Modifier.weight(1f),
                    testTag = "session_trade_count"
                )
            }

            Spacer8()

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "$wins W",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = StatGreen
                )
                Text(text = "·", fontSize = 12.sp, color = TextSecondary)
                Text(
                    text = "$losses L",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = StatRed
                )
                Text(text = "·", fontSize = 12.sp, color = TextSecondary)
                Text(
                    text = "Staked ${String.format(Locale.US, "%.2f", summary?.totalStaked ?: 0.0)}",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }

            Spacer8()

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF171A24), RoundedCornerShape(10.dp))
                    .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
                    .padding(12.dp)
                    .testTag("session_last_trade")
            ) {
                if (lastTrade == null) {
                    Text(
                        text = "No confirmed trade yet in this session.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Last trade · ${lastTrade.marketName}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = "${lastTrade.outcome} at ${tradeFormat.format(Date(lastTrade.settledAt))}",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                        Text(
                            text = String.format(Locale.US, "%+.2f", lastTrade.simulatedNetResult),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (lastTrade.simulatedNetResult >= 0) StatGreen else StatRed
                        )
                    }
                }
            }

            Spacer8()

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = "Totals come from confirmed Deriv contract outcomes only. Trades still " +
                        "settling are excluded until they resolve.",
                    fontSize = 10.sp,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun Spacer8() = Box(modifier = Modifier.padding(top = 8.dp))

@Composable
private fun SessionStatusPill(status: String?) {
    val active = status == "ACTIVE"
    Box(
        modifier = Modifier
            .background(
                if (active) Color(0xFF0F2419) else Color(0xFF232734),
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = status ?: "—",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (active) StatGreen else TextSecondary
        )
    }
}

@Composable
private fun MetaChip(label: String, value: String, emphasis: Boolean) {
    Box(
        modifier = Modifier
            .background(Color(0xFF232734), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = "$label: $value",
            fontSize = 11.sp,
            fontWeight = if (emphasis) FontWeight.Bold else FontWeight.Normal,
            color = if (emphasis) VividYellow else TextSecondary
        )
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    testTag: String = ""
) {
    Column(
        modifier = modifier
            .background(Color(0xFF171A24), RoundedCornerShape(10.dp))
            .padding(12.dp)
            .testTag(testTag)
    ) {
        Text(text = label, fontSize = 11.sp, color = TextSecondary)
        Text(text = value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = valueColor)
    }
}
