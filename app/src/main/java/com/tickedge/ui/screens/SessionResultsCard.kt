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
import com.tickedge.ui.components.AccountRibbon
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
 *
 * @param previousSession the most recent finished session with results. Pressing Start opens a new
 *   session that has, by definition, no trades yet — so every figure on this card fell to $0.00 the
 *   instant the user started the next run, which reads as the history having been wiped even though
 *   it is all still in the database. When the live session has nothing settled, the last finished
 *   session is shown instead, clearly labelled, until the new one produces its first result.
 */
@Composable
fun SessionResultsCard(
    session: SimulationSessionEntity?,
    summary: SessionSummary?,
    lastTrade: LastTradeRow?,
    previousSession: SimulationSessionEntity? = null,
    modifier: Modifier = Modifier
) {
    val startedFormat = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US) }
    val tradeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    val liveTrades = summary?.completedTrades ?: 0
    // A session with nothing settled has no results to report. If a finished session does, that is
    // the more useful thing to show, as long as it says so. Resolved to a nullable rather than a
    // boolean so every read below goes through `?.` — the pair can never disagree about whether
    // there is a previous session to read from.
    val liveHasResults = liveTrades > 0 || lastTrade != null
    val shownPrevious = previousSession?.takeIf { !liveHasResults && it.totalTrades > 0 }
    val shown = shownPrevious ?: session

    // Figures come from SQL over the trades while a session is running, and from the row's own
    // stored totals once it is closed — the same numbers `closeSession` wrote from that SQL.
    val realized = shownPrevious?.netProfit ?: (summary?.totalRealizedPnl ?: 0.0)
    val wins = shownPrevious?.winCount ?: (summary?.winCount ?: 0)
    val losses = shownPrevious?.lossCount ?: (summary?.lossCount ?: 0)
    val completed = shownPrevious?.totalTrades ?: liveTrades
    val grossProfit = shownPrevious?.grossProfit ?: (summary?.grossProfit ?: 0.0)
    val grossLoss = shownPrevious?.grossLoss ?: (summary?.grossLoss ?: 0.0)
    val staked = shownPrevious?.totalStaked ?: (summary?.totalStaked ?: 0.0)

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
        modifier = modifier.fillMaxWidth().testTag("session_results_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (shownPrevious != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2C2812), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("session_showing_previous")
                ) {
                    Text(
                        text = "Showing your last finished session (#${shownPrevious.id}). " +
                            "Session #${session?.id ?: "—"} is running and has no settled trades yet.",
                        fontSize = 11.sp,
                        color = VividYellow
                    )
                }
                Spacer8()
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (shown != null) "Session #${shown.id}" else "No session yet",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = if (shown != null) {
                            // Both ends, because a session that silently never closes reads as one
                            // long run. Before the engine reported its own exits, a run stopped by its
                            // stop loss left no `endedAt` at all and the next Start inherited the
                            // session, so the two runs shared one timestamp pair.
                            buildString {
                                append("Started ")
                                append(startedFormat.format(Date(shown.startedAt)))
                                shown.endedAt?.let {
                                    append(" · Ended ")
                                    append(startedFormat.format(Date(it)))
                                }
                            }
                        } else {
                            "Press Start Automated Trading to open one."
                        },
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
                SessionStatusPill(shown?.status)
            }

            if (shown != null) {
                Spacer8()
                // The account type is stated before the totals, not implied by them. This card sits
                // at the very top of the History page and carries the headline P&L, so it is the
                // most screenshot-worthy thing on the screen.
                AccountRibbon(
                    isReal = shown.isReal,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetaChip(
                        label = "Account",
                        value = shown.accountId.ifBlank { "unknown" },
                        emphasis = shown.isReal
                    )
                    MetaChip(label = "Strategy", value = shown.strategy, emphasis = false)
                }
            }

            Spacer8()

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
                    text = "Staked ${String.format(Locale.US, "%.2f", staked)}",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }

            // Gross profit and gross loss shown apart, because the net alone hides the shape of a
            // run: +$2.00 net from five $5 wins and two $11.50 losses is a very different session
            // from +$2.00 net off two small wins, and only the split tells you which one happened.
            Spacer8()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Profit ${String.format(Locale.US, "%.2f", grossProfit)}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = StatGreen
                )
                Text(text = "·", fontSize = 12.sp, color = TextSecondary)
                Text(
                    text = "Loss ${String.format(Locale.US, "%.2f", grossLoss)}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = StatRed
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
                if (shownPrevious != null) {
                    Text(
                        text = "Last trade of session #${shownPrevious.id} is in the Trades tab.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                } else if (lastTrade == null) {
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
