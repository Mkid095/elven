package com.tickedge.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tickedge.data.local.SimulationPositionEntity
import com.tickedge.data.local.SimulationSessionEntity
import com.tickedge.domain.StrategyCatalog
import com.tickedge.ui.components.AccountChip
import com.tickedge.ui.components.SimpleLineChart
import com.tickedge.ui.components.demoAwareBorder
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
 * The running P&L curve for a session's trades, for the detail sheet's chart.
 *
 * Starts at 0 and walks the settled trades in settle order, so the line is equity over the run —
 * not a re-derivation against today's ladder.
 */
private fun pnlSeries(trades: List<SimulationPositionEntity>): List<Double> {
    val settled = trades.filter { it.outcome == "WON" || it.outcome == "LOST" }.sortedBy { it.settledAt }
    if (settled.isEmpty()) return emptyList()
    val out = ArrayList<Double>(settled.size + 1)
    out.add(0.0)
    var running = 0.0
    for (t in settled) {
        running += t.simulatedNetResult
        out.add(running)
    }
    return out
}

/**
 * One session in the Sessions list.
 *
 * Tappable — it opens that session's P&L curve and trades in the detail sheet. The figures shown
 * are the run's own snapshot as recorded when it closed; a live session is flagged and, when
 * tapped, its sheet reads the trades straight from the database so it ticks.
 */
@Composable
internal fun SessionRow(
    session: SimulationSessionEntity,
    isActive: Boolean,
    onTap: () -> Unit
) {
    val dayFormat = remember { SimpleDateFormat("HH:mm", Locale.US) }
    val net = session.netProfit
    val running = session.status == "ACTIVE"
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            when {
                isActive -> VividYellow.copy(alpha = 0.6f)
                else -> demoAwareBorder(session.isReal)
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .testTag("session_row_${session.id}")
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Session #${session.id}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        AccountChip(isReal = session.isReal)
                        Text(
                            text = session.strategy + " · " +
                                StrategyCatalog.find(session.strategyId).risk.tier,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = VividYellow
                        )
                    }
                    Text(
                        text = buildString {
                            append(dayFormat.format(Date(session.startedAt)))
                            append(" → ")
                            append(session.endedAt?.let { dayFormat.format(Date(it)) } ?: "running")
                        },
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (running) "ACTIVE" else session.status,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (running) VividYellow else TextSecondary
                    )
                    Text(
                        text = String.format(Locale.US, "%s$%.2f", if (net >= 0) "+" else "", net),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (net >= 0) StatGreen else StatRed
                    )
                }
            }
            Text(
                text = "${session.totalTrades} trades · ${session.winCount}W / ${session.lossCount}L" +
                    " · tap for P&L curve & trades",
                fontSize = 11.sp,
                color = TextSecondary
            )
        }
    }
}

/**
 * The expandable sheet for one session: its P&L curve plus its own trades.
 *
 * This is where a session's record is browsed instead of flooding the main Trade History — the
 * curve is equity over this session's settled trades, and the list is only this session's rows.
 */
@Composable
internal fun SessionDetailContent(
    session: SimulationSessionEntity,
    trades: List<SimulationPositionEntity>,
    onSelectTrade: (SimulationPositionEntity) -> Unit,
    timeFormatter: SimpleDateFormat,
    modifier: Modifier = Modifier
) {
    val series = remember(trades) { pnlSeries(trades) }
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Session #${session.id}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                AccountChip(isReal = session.isReal)
                Text(
                    text = if (session.status == "ACTIVE") "ACTIVE" else "COMPLETED",
                    fontSize = 11.sp,
                    color = if (session.status == "ACTIVE") VividYellow else TextSecondary
                )
            }
            Text(
                text = session.strategy + " · " +
                    String.format(
                        Locale.US, "%s$%.2f",
                        if (session.netProfit >= 0) "+" else "", session.netProfit
                    ),
                fontSize = 12.sp,
                color = TextSecondary
            )
        }

        Text(text = "P&L curve", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        if (series.size >= 2) {
            SimpleLineChart(points = series, lineColor = if (session.netProfit >= 0) StatGreen else StatRed)
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDarkCard, RoundedCornerShape(10.dp))
                    .then(Modifier.border(1.dp, BorderDark, RoundedCornerShape(10.dp)))
                    .height(90.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No settled trades yet", fontSize = 12.sp, color = TextSecondary)
            }
        }

        if (trades.isEmpty()) {
            Text("This session has no trades recorded.", fontSize = 12.sp, color = TextSecondary)
        } else {
            Text(text = "Trades in this session", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            trades.forEach { pos ->
                TradeRow(pos = pos, timeFormatter = timeFormatter, onSelect = onSelectTrade)
            }
        }
    }
}
