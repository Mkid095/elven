package com.tickedge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tickedge.data.local.LastTradeRow
import com.tickedge.data.local.SessionSummary
import com.tickedge.data.local.SimulationPositionEntity
import com.tickedge.data.local.SimulationSessionEntity
import com.tickedge.ui.components.AccountRibbon
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
 * Performance & history, active-session-first.
 *
 * The header, the Trades tab and the P&L tab all describe the *current* run and nothing else, so a
 * session's figures never bleed into the next one and the numbers always agree with the trades
 * showing. They are driven by the live [SessionSummary] (SQL over the running session's settled
 * trades), not the session entity's cached totals — which is why they keep ticking for a run that
 * has been going for hours. When nothing is running the three collapse to a "no active session"
 * prompt rather than showing a stale or all-time figure.
 *
 * Everything before the current run lives in the Sessions tab, listed by day; tapping a session
 * opens the detail sheet — that session's P&L curve plus its own trades — instead of letting every
 * old trade pile up in the main lists.
 *
 * The pieces are split across sibling files in this package: [TradeRow], [SessionRow] and
 * [SessionDetailContent] for the session views, and [TradeDetailSheet]/[DetailTile] for the trade
 * drill-down.
 */
@Composable
fun HistoryScreen(
    activeSession: SimulationSessionEntity?,
    sessionSummary: SessionSummary?,
    lastConfirmedTrade: LastTradeRow?,
    /** The running session's own trades only. */
    sessionTrades: List<SimulationPositionEntity>,
    /** Every session this user has run (local + pulled from a sibling), newest first. */
    sessions: List<SimulationSessionEntity>,
    /** The session opened in the detail sheet, if any. */
    detailSession: SimulationSessionEntity?,
    /** That session's trades, for the sheet's P&L curve and list. */
    detailTrades: List<SimulationPositionEntity>,
    onOpenSessionDetail: (SimulationSessionEntity?) -> Unit,
    activeAccountIsReal: Boolean = false,
    /** Optional "start a run" action for the idle state. Absent when the screen can't start one. */
    onStartRun: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf("Trades") } // "Trades", "P&L", "Sessions"
    var selectedTrade by remember { mutableStateOf<SimulationPositionEntity?>(null) }
    val onSelectTrade: (SimulationPositionEntity) -> Unit = { selectedTrade = it }

    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.US) }
    val idle = activeSession == null

    // The detail sheet sits outside the LazyColumn so dismissing it does not disturb the list.
    detailSession?.let { sess ->
        @OptIn(ExperimentalMaterial3Api::class)
        ModalBottomSheet(
            onDismissRequest = { onOpenSessionDetail(null) },
            sheetState = rememberModalBottomSheetState(),
            containerColor = SurfaceDarkCard,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            SessionDetailContent(
                session = sess,
                trades = detailTrades,
                onSelectTrade = onSelectTrade,
                timeFormatter = timeFormatter,
                modifier = Modifier.padding(bottom = 28.dp)
            )
        }
    }

    selectedTrade?.let { trade ->
        TradeDetailSheet(
            trade = trade,
            onDismiss = { selectedTrade = null }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D0E12))
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header: the account in use plus the running session's live trade count and P&L so far.
        item {
            AccountRibbon(
                isReal = activeSession?.isReal ?: activeAccountIsReal,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            if (idle) {
                Text("No active session", fontSize = 13.sp, color = TextSecondary)
            } else {
                val trades = sessionSummary?.completedTrades ?: 0
                val pnl = sessionSummary?.totalRealizedPnl ?: 0.0
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${activeSession?.id?.let { "Session #$it" } ?: "Session"} · " +
                            "${activeSession?.strategy ?: ""} · $trades trades",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Text(
                        text = String.format(
                            Locale.US, "%s$%.2f",
                            if (pnl >= 0) "+" else "", pnl
                        ) + " so far",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (pnl >= 0) StatGreen else StatRed
                    )
                }
            }
        }

        // Screen title
        item {
            Text(
                text = "Performance & History",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }

        // Tabs
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDarkCard, RoundedCornerShape(12.dp))
                    .padding(4.dp)
            ) {
                listOf(
                    "Trades" to "Trade History",
                    "P&L" to "Profit & Loss",
                    "Sessions" to "Sessions"
                ).forEach { (key, label) ->
                    val isSel = selectedTab == key
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSel) VividYellow else Color.Transparent)
                            .clickable { selectedTab = key }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSel) Color(0xFF101216) else TextSecondary
                        )
                    }
                }
            }
        }

        // TAB 1: TRADE HISTORY — the running session's trades only.
        if (selectedTab == "Trades") {
            if (idle || sessionTrades.isEmpty()) {
                item { NoActiveSessionPrompt(onStartRun, "No trades yet in the active session.") }
            } else {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Time", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.width(42.dp))
                        Text(text = "Market", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.width(56.dp))
                        Text(text = "Type", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.width(52.dp))
                        Text(text = "Bet", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.width(38.dp))
                        Text(text = "P/L", fontSize = 12.sp, color = TextSecondary)
                    }
                }
                items(sessionTrades, key = { "trade_${it.contractId}" }) { pos ->
                    TradeRow(pos = pos, timeFormatter = timeFormatter, onSelect = onSelectTrade)
                }
            }
        }

        // TAB 2: PROFIT & LOSS — the running session's live P&L only. No all-time accumulation.
        if (selectedTab == "P&L") {
            if (idle || sessionSummary == null) {
                item { NoActiveSessionPrompt(onStartRun, "No P&L yet in the active session.") }
            } else {
                val summary = sessionSummary!!
                val winRate = if (summary.completedTrades > 0)
                    (summary.winCount * 100 / summary.completedTrades) else 0
                item {
                    Text(text = "This session", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DetailTile(
                            label = "Net P&L",
                            value = String.format(
                                Locale.US, "%s$%.2f",
                                if (summary.totalRealizedPnl >= 0) "+" else "",
                                summary.totalRealizedPnl
                            ),
                            tint = if (summary.totalRealizedPnl >= 0) StatGreen else StatRed,
                            modifier = Modifier.weight(1f)
                        )
                        DetailTile(
                            label = "Trades",
                            value = summary.completedTrades.toString(),
                            tint = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DetailTile(
                            label = "Win rate",
                            value = "$winRate% (${summary.winCount}W / ${summary.lossCount}L)",
                            tint = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        DetailTile(
                            label = "Staked",
                            value = String.format(Locale.US, "$%.2f", summary.totalStaked),
                            tint = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DetailTile(
                            label = "Gross profit",
                            value = String.format(Locale.US, "$%.2f", summary.grossProfit),
                            tint = StatGreen,
                            modifier = Modifier.weight(1f)
                        )
                        DetailTile(
                            label = "Gross loss",
                            value = String.format(Locale.US, "$%.2f", summary.grossLoss),
                            tint = StatRed,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                lastConfirmedTrade?.let { last ->
                    item {
                        Text(
                            text = "Last: " +
                                String.format(
                                    Locale.US, "%s$%.2f",
                                    if (last.simulatedNetResult >= 0) "+" else "",
                                    last.simulatedNetResult
                                ),
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        // TAB 3: SESSIONS — every past run, by day. Tap one to open its detail sheet.
        if (selectedTab == "Sessions") {
            if (sessions.isEmpty()) {
                item {
                    Text(
                        text = "No sessions recorded yet.",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                sessions
                    .groupBy { dayKey(it.startedAt) }
                    .toList()
                    .sortedByDescending { it.first }
                    .forEach { (day, daySessions) ->
                        item(key = "day_$day") {
                            val dayNet = daySessions.sumOf { it.netProfit }
                            val dayTrades = daySessions.sumOf { it.totalTrades }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = dayLabel(day),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = VividYellow
                                )
                                Text(
                                    text = "${daySessions.size} session" +
                                        (if (daySessions.size == 1) "" else "s") +
                                        " · $dayTrades trades · " +
                                        String.format(
                                            Locale.US, "%s$%.2f",
                                            if (dayNet >= 0) "+" else "", dayNet
                                        ),
                                    fontSize = 11.sp,
                                    color = if (dayNet >= 0) StatGreen else StatRed
                                )
                            }
                        }
                        daySessions.forEach { session ->
                            item(key = "session_${session.id}") {
                                SessionRow(
                                    session = session,
                                    isActive = session.id == activeSession?.id,
                                    onTap = { onOpenSessionDetail(session) }
                                )
                            }
                        }
                    }
            }
        }
    }
}

/**
 * The idle state for the Trades and P&L tabs: nothing is running, so there is nothing to show yet.
 *
 * Deliberately a prompt rather than a fallback to an all-time figure — an all-time number here
 * would be the exact "contradiction" the screen is meant to avoid.
 */
@Composable
private fun NoActiveSessionPrompt(onStartRun: (() -> Unit)?, sub: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .background(SurfaceDarkCard, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "No active session",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(text = sub, fontSize = 12.sp, color = TextSecondary)
            onStartRun?.let {
                Button(onClick = it) {
                    Text(text = "Start a run", fontSize = 13.sp, color = Color(0xFF101216))
                }
            }
        }
    }
}

/** Local-calendar day key (yyyy-DDD) for grouping sessions. */
private fun dayKey(millis: Long): String {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = millis
    return "${cal.get(java.util.Calendar.YEAR)}-" +
        "%03d".format(cal.get(java.util.Calendar.DAY_OF_YEAR))
}

private fun dayLabel(key: String): String {
    val parts = key.split("-")
    val cal = java.util.Calendar.getInstance()
    cal.set(java.util.Calendar.YEAR, parts[0].toInt())
    cal.set(java.util.Calendar.DAY_OF_YEAR, parts[1].toInt())
    return SimpleDateFormat("EEEE d MMM yyyy", Locale.US).format(cal.time)
}
