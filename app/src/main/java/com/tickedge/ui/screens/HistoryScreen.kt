package com.tickedge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tickedge.data.local.LastTradeRow
import com.tickedge.data.local.SessionSummary
import com.tickedge.data.local.SimulationPositionEntity
import com.tickedge.data.local.SimulationSessionEntity
import com.tickedge.domain.RecoveryCalculator
import com.tickedge.domain.StrategyCatalog
import com.tickedge.ui.components.AccountChip
import com.tickedge.ui.components.AccountRibbon
import com.tickedge.ui.components.DemoStyle
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

/** The display strings for one trade row, computed once per row (see [TradeRow]). */
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
 * The running P&L curve for a session's trades, for the detail sheet's chart.
 *
 * Starts at 0 and walks the settled trades in settle order, so the line is equity over the run —
 * not a re-derivation against today's ladder. Prepending 0 keeps the baseline honest.
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
 * opens [SessionDetailSheet] — that session's P&L curve plus its own trades — instead of letting
 * every old trade pile up in the main lists.
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
                Text(
                    text = "No active session",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
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
                listOf("Trades" to "Trade History", "P&L" to "Profit & Loss", "Sessions" to "Sessions").forEach { (key, label) ->
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
                    TradeRow(
                        pos = pos,
                        timeFormatter = timeFormatter,
                        onSelect = onSelectTrade
                    )
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
                    Text(
                        text = "This session",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
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
                                        String.format(Locale.US, "%s$%.2f", if (dayNet >= 0) "+" else "", dayNet),
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
 * would be the exact "contradiction" the screen is meant to avoid, mixing a dead session's total
 * into what the user reads as "now".
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
            Text(
                text = sub,
                fontSize = 12.sp,
                color = TextSecondary
            )
            onStartRun?.let {
                Button(onClick = it) {
                    Text(text = "Start a run", fontSize = 13.sp, color = Color(0xFF101216))
                }
            }
        }
    }
}

/**
 * One session in the Sessions list.
 *
 * Tappable — it opens that session's P&L curve and trades in the detail sheet. The figures shown
 * are the run's own snapshot as recorded when it closed; a live session is flagged and, when
 * tapped, its sheet reads the trades straight from the database so it ticks.
 */
@Composable
private fun SessionRow(
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
        border = androidx.compose.foundation.BorderStroke(
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
private fun SessionDetailContent(
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
                    .height(90.dp)
                    .background(Color(0xFF13151D), RoundedCornerShape(10.dp))
                    .border(1.dp, BorderDark, RoundedCornerShape(10.dp)),
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

/**
 * One trade row, recycled across the Trades tab and the session detail sheet.
 *
 * The display strings are built once per row and cached on the entity, so recomposition on a settle
 * does not re-run the date/label work for every visible row.
 */
@Composable
private fun TradeRow(
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
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DemoStyle.tint(pos.isReal).copy(alpha = 0.30f)),
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

/**
 * Full detail for one trade.
 *
 * Every figure here is what was recorded **at the time of the trade**. The recovery snapshot in
 * particular is deliberately not recomputed from the current ladder: it says which rung this was and
 * what it was clearing, which is the only way a win makes sense next to the losses before it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TradeDetailSheet(
    trade: SimulationPositionEntity,
    onDismiss: () -> Unit
) {
    val stamp = remember { SimpleDateFormat("d MMM yyyy, HH:mm:ss", Locale.US) }
    val won = trade.outcome == "WON"
    // Which strategy placed this trade, straight from the row's own stamp where it has one.
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
                        trade.strategyId.isNotEmpty() ->
                            StrategyCatalog.find(trade.strategyId).risk.label
                        trade.scenarioType.contains("Even", ignoreCase = true) ->
                            StrategyCatalog.EVEN_ODD.risk.label
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
                    if (trade.askPrice > 0.0) String.format(Locale.US, "$%.2f", trade.askPrice)
                    else "Unavailable"
                )
                DetailRow(
                    "Payout",
                    if (trade.simulatedPayout > 0.0) String.format(Locale.US, "$%.2f", trade.simulatedPayout)
                    else "None"
                )
                DetailRow("Status", trade.outcome)
                DetailRow("Account", trade.accountId.ifBlank { "unknown" })
                DetailRow("Session", "#${trade.sessionId.takeIf { it != 0L } ?: "—"}")
                DetailRow("Opened", stamp.format(Date(trade.openedAt)))
                DetailRow("Settled", stamp.format(Date(trade.settledAt)))
                DetailRow(
                    "Contract id",
                    trade.contractId.ifBlank { "Not recorded" },
                    monospace = true
                )
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
                    Text(
                        text = "Recovery context",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = VividYellow
                    )
                    Text(
                        text = "Ladder rung ${trade.recoveryStep}",
                        fontSize = 12.sp,
                        color = TextPrimary
                    )
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
private fun DetailTile(
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
private fun DetailRow(label: String, value: String, monospace: Boolean = false) {
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
