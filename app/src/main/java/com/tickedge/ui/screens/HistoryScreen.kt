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
import androidx.compose.foundation.layout.size
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
import com.tickedge.ui.components.AccountSectionHeader
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

/**
 * One account's results, kept whole.
 *
 * Grouped rather than filtered in place so a demo trade can never end up sitting between two live
 * ones — an interleaved list reads as a single record, and the reader has no way to know the
 * boundary was crossed.
 *
 * `settled` and `netPnl` are computed once, in the constructor, rather than as `val get()`:
 * with an uncapped list the getters would walk the whole record on every recomposition, and the
 * LazyColumn recomposes whenever a trade settles.
 */
private data class AccountBlock(
    val isReal: Boolean,
    val positions: List<SimulationPositionEntity>,
    val sessions: List<SimulationSessionEntity>
) {
    val settled: List<SimulationPositionEntity> =
        positions.filter { it.outcome == "WON" || it.outcome == "LOST" }

    val netPnl: Double = positions.sumOf { it.simulatedNetResult }
}

/**
 * The display strings for one trade row, computed once per row.
 *
 * The LazyColumn recycles rows as the user scrolls, and every settle re-emits the positions list,
 * so running `Date`/`String.format`/`strategyOf` inline would redo them on every recomposition of
 * every visible row. The values are built in the `remember` keyed to the entity, which only
 * re-runs when the row's data actually changes.
 */
private data class TradeRowView(
    val pos: SimulationPositionEntity,
    val timeText: String,
    val typeText: String,
    val betText: String,
    val pnlText: String,
    val riskTier: String
)

@Composable
fun HistoryScreen(
    positions: List<SimulationPositionEntity>,
    /**
     * Every session this user has run. The Sessions & Days tab lists these; it cannot be derived
     * from [positions], because a session with no settled trades is still a session.
     */
    sessions: List<SimulationSessionEntity> = emptyList(),
    activeSession: SimulationSessionEntity?,
    sessionSummary: SessionSummary?,
    lastConfirmedTrade: LastTradeRow?,
    /**
     * Whether the account currently in use is the live one.
     *
     * The History page is scoped to this account, so the badge is stated rather than inferred from
     * the rows: an empty live history has no rows to infer from, and "no history" is exactly the
     * state where the user most needs to know which account they are looking at.
     */
    activeAccountIsReal: Boolean = false,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf("Trades") } // "Trades", "P&L", "Sessions"
    var typeFilter by remember { mutableStateOf("All") } // "All", "Differs", "Even / Odd"
    // The P&L tab keeps account-level totals; this isolates which strategy's trades feed them.
    var pnlStrategy by remember { mutableStateOf("All") } // "All", "Differs", "Even / Odd"
    var pnlPeriod by remember { mutableStateOf("Today") } // "Today", "7 Days", "30 Days"
    // The trade whose detail sheet is open, or null. Held here rather than lifted to the nav host
    // because the sheet is scoped entirely to this screen and never survives a tab change.
    var selectedTrade by remember { mutableStateOf<SimulationPositionEntity?>(null) }
    val onSelectTrade: (SimulationPositionEntity) -> Unit = { selectedTrade = it }

    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.US) }

    /**
     * Which strategy placed a trade, from the row's own stamp rather than the session it belongs to.
     *
     * The id is recorded by the engine that made the trade; only rows written before the stamp
     * existed (or by a very old build on another device) fall back to the scenario type, where
     * "Even / Odd" is the marker of the parity strategy and everything else is Differs.
     */
    fun strategyOf(pos: SimulationPositionEntity): String =
        when {
            pos.strategyId.isNotEmpty() -> pos.strategyId
            pos.scenarioType.contains("Even", ignoreCase = true) -> "even_odd"
            else -> StrategyCatalog.DEFAULT.id
        }

    fun strategyName(id: String): String =
        StrategyCatalog.find(id).name

    /** The contract-type label for the Type column: Differs, O/U, or Even/Odd by side. */
    fun typeLabel(pos: SimulationPositionEntity): String = when (strategyOf(pos)) {
        "even_odd" -> if (pos.targetDigit == 0) "E/O·Even" else "E/O·Odd"
        else -> if (pos.scenarioType.contains("Differs")) "Differs" else "O/U"
    }

    /**
     * The last-column value: for Differs the digit IS the bet, so the digit. For Even/Odd the
     * bet is the parity class, not any digit — 0/9 is only the encoding on disk, and printing
     * it reads as "the bot bet on 9" when it actually bet "Even" on the whole market.
     */
    fun betLabel(pos: SimulationPositionEntity): String = when (strategyOf(pos)) {
        "even_odd" -> if (pos.targetDigit == 0) "Even" else "Odd"
        else -> if (pos.targetDigit >= 0) pos.targetDigit.toString() else "—"
    }

    // Both accounts are on screen at once, in separate blocks with the live block above the demo
    // block.
    //
    // Scoping the whole page to one account was the wrong correction: it hid a real account's
    // results whenever the user was looking at demo, which is its own kind of misleading. Showing
    // both — clearly partitioned, each block headed by its own account type and its own totals —
    // keeps every record visible while making it impossible to add a demo profit to a live one.
    val livePositions = positions.filter { it.isReal }
    val demoPositions = positions.filter { !it.isReal }

    val tradesAreReal = activeAccountIsReal
    val tradesAreMixed = false

    /** Account blocks in the order they are rendered: live first, demo second. */
    val accountBlocks = listOf(
        AccountBlock(
            isReal = true,
            positions = livePositions,
            sessions = sessions.filter { it.isReal }
        ),
        AccountBlock(
            isReal = false,
            positions = demoPositions,
            sessions = sessions.filter { !it.isReal }
        )
    ).filter { it.positions.isNotEmpty() || it.sessions.isNotEmpty() }

    // The detail sheet sits outside the LazyColumn so it is not an item that scrolls away with the
    // list behind it, and so dismissing it does not disturb the list's scroll position.
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
        // Session results — confirmed P&L for the current run, above the per-trade list.
        item {
            // The last finished session that actually produced results, so the card has something
            // real to show in the moment between "I pressed Start" and "my first trade settled".
            val previousWithResults = sessions.firstOrNull {
                it.id != activeSession?.id && it.status == "COMPLETED" && it.totalTrades > 0
            }
            SessionResultsCard(
                session = activeSession,
                summary = sessionSummary,
                lastTrade = lastConfirmedTrade,
                previousSession = previousWithResults
            )
        }

        // Screen Title
        item {
            Text(
                text = "Performance & History",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }

        // Top Main Navigation Tabs
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDarkCard, RoundedCornerShape(12.dp))
                    .padding(4.dp)
            ) {
                listOf("Trades" to "Trade History", "P&L" to "Profit & Loss", "Sessions" to "Sessions & Days").forEach { (key, label) ->
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

        // TAB 1: TRADE HISTORY
        if (selectedTab == "Trades") {
            item {
                // Filter chips by strategy: All, Differs, Even / Odd.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("All", "Differs", "Even / Odd").forEach { f ->
                        val isSel = typeFilter == f
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) Color(0xFF2C2812) else SurfaceDarkCard)
                                .border(1.dp, if (isSel) VividYellow else BorderDark, RoundedCornerShape(8.dp))
                                .clickable { typeFilter = f }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = f,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isSel) VividYellow else TextSecondary
                            )
                        }
                    }
                }
            }

            if (accountBlocks.isEmpty()) {
                item {
                    AccountRibbon(
                        isReal = tradesAreReal,
                        isMixed = tradesAreMixed,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .background(SurfaceDarkCard, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (tradesAreReal) {
                                "No live trades recorded yet."
                            } else {
                                "No demo trades recorded yet."
                            },
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                accountBlocks.forEach { block ->
                    val shown = block.positions.filter {
                        when (typeFilter) {
                            "All" -> true
                            "Differs" -> strategyOf(it) != "even_odd"
                            "Even / Odd" -> strategyOf(it) == "even_odd"
                            else -> true
                        }
                    }

                    item(key = "section_${block.isReal}") {
                        AccountSectionHeader(
                            isReal = block.isReal,
                            tradeCount = block.settled.size,
                            netPnl = block.netPnl,
                            isCurrentAccount = block.isReal == tradesAreReal,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    item(key = "header_${block.isReal}") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Time", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.width(42.dp))
                            Text(text = "Market", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.width(56.dp))
                            Text(text = "Type", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.width(52.dp))
                            Text(text = "Bet", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.width(38.dp))
                            Text(text = "P/L", fontSize = 12.sp, color = TextSecondary)
                        }
                    }

                    if (shown.isEmpty()) {
                        item(key = "empty_${block.isReal}") {
                            Text(
                                text = "No ${DemoStyle.label(block.isReal).lowercase()} trades match this filter.",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    } else {
                        items(shown, key = { "trade_${it.contractId}_${block.isReal}" }) { pos ->
                            // The row's display strings, built once and cached per row: with an
                            // uncapped list, running `Date`/`String.format`/`strategyOf` on every
                            // recomposition is what made the page freeze. The key is the entity,
                            // so the cache only re-runs when the trade itself changes.
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
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    DemoStyle.tint(block.isReal).copy(alpha = 0.30f)
                                ),
                                // Tappable. The rows had no click handler at all, so tapping a trade
                                // did nothing — the list showed amounts with no way to see the contract
                                // behind them. `clickable` rather than a nested one so the whole row is
                                // the target.
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectTrade(row.pos) }
                                    .testTag("trade_row_${row.pos.contractId}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = row.timeText,
                                        fontSize = 12.sp,
                                        color = TextSecondary,
                                        modifier = Modifier.width(42.dp)
                                    )
                                    Text(
                                        text = row.pos.marketName,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary,
                                        modifier = Modifier.width(56.dp)
                                    )
                                    Text(
                                        text = row.typeText,
                                        fontSize = 12.sp,
                                        color = TextSecondary,
                                        modifier = Modifier.width(52.dp)
                                    )
                                    // What the trade actually bet: for a Differs contract the digit
                                    // IS the bet, so the digit; for an Even/Odd contract the bet is
                                    // the parity class, and printing the 0/9 encoding would read as
                                    // "the bot bet on 9" — which it did not.
                                    Text(
                                        text = row.betText,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = VividYellow,
                                        modifier = Modifier.width(38.dp)
                                    )
                                    // The badge sits on the row itself, not only on the section
                                    // header above it. A screenshot cropped to a single line of
                                    // results would otherwise carry the profit figure and nothing
                                    // about whether it was real money.
                                    Column(horizontalAlignment = Alignment.End) {
                                        AccountChip(isReal = row.pos.isReal)
                                        Text(
                                            text = row.pnlText,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isWin) StatGreen else StatRed
                                        )
                                        // Which strategy placed this trade, and its risk tier, so
                                        // a row can be read without opening the detail sheet.
                                        Text(
                                            text = row.riskTier,
                                            fontSize = 10.sp,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // TAB 2: PROFIT & LOSS
        if (selectedTab == "P&L") {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("All strategies", "Differs", "Even / Odd").forEach { s ->
                        val isSel = pnlStrategy == s
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) Color(0xFF2C2812) else SurfaceDarkCard)
                                .border(1.dp, if (isSel) VividYellow else BorderDark, RoundedCornerShape(8.dp))
                                .clickable { pnlStrategy = s }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = s,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSel) VividYellow else TextSecondary
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Today", "7 Days", "90 Days").forEach { p ->
                        val isSel = pnlPeriod == p
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) VividYellow else SurfaceDarkCard)
                                .clickable { pnlPeriod = p }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = p,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSel) Color(0xFF101216) else TextSecondary
                            )
                        }
                    }
                }
            }

            // One card per account, live above demo. Previously this was a single card summing
            // both accounts, which is a claim about a balance that does not exist — and it let a
            // profitable demo run carry a live-looking total.
            accountBlocks.forEach { block ->
                val windowStart = periodStartMillis(pnlPeriod)
                // Account-level totals, filtered by strategy so each card says whose P&L it is.
                val strategyScoped = block.positions.filter {
                    when (pnlStrategy) {
                        "Differs" -> strategyOf(it) != "even_odd"
                        "Even / Odd" -> strategyOf(it) == "even_odd"
                        else -> true
                    }
                }
                val inPeriod = strategyScoped.filter { it.settledAt >= windowStart }

                // Settlement-poll timeouts are persisted as outcome="PENDING" with a zero net.
                // Counting them as trades permanently depressed the win rate, so they are
                // excluded from both the denominator and the numerator.
                val settled = inPeriod.filter { it.outcome == "WON" || it.outcome == "LOST" }
                val totalTrades = settled.size
                val winCount = settled.count { it.outcome == "WON" }
                val totalPnl = inPeriod.sumOf { it.simulatedNetResult }
                val totalStake = inPeriod.sumOf { it.simulatedStake }
                val totalReturn = inPeriod.sumOf { it.simulatedPayout }
                val winRate = if (totalTrades > 0) ((winCount.toDouble() / totalTrades.toDouble()) * 100).toInt() else 0

                item(key = "pnl_card_${block.isReal}") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DemoStyle.tint(block.isReal)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AccountSectionHeader(
                                    isReal = block.isReal,
                                    tradeCount = totalTrades,
                                    netPnl = totalPnl,
                                    isCurrentAccount = block.isReal == activeAccountIsReal,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(text = "Total P/L", fontSize = 12.sp, color = TextSecondary)
                                        AccountChip(isReal = block.isReal)
                                    }
                                    val pnlSign = if (totalPnl >= 0) "+" else ""
                                    Text(
                                        text = String.format(Locale.US, "%s$%.2f", pnlSign, totalPnl),
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Black,
                                        color = if (totalPnl >= 0) StatGreen else StatRed
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(text = "Total Trades", fontSize = 12.sp, color = TextSecondary)
                                    Text(text = "$totalTrades", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                }
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text(text = "Win Rate", fontSize = 12.sp, color = TextSecondary)
                                    Text(text = "$winRate%", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                }
                                Column {
                                    Text(text = "Total Stake", fontSize = 12.sp, color = TextSecondary)
                                    Text(text = String.format(Locale.US, "$%.2f", totalStake), fontSize = 16.sp, color = TextSecondary)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(text = "Total Return", fontSize = 12.sp, color = TextSecondary)
                                    Text(text = String.format(Locale.US, "$%.2f", totalReturn), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = VividYellow)
                                }
                            }

                            // What risk the P&L is built from, stated on the card itself: a
                            // strategy-scoped card shows that strategy's tier and win rate;
                            // "All strategies" names both, since their blended figure hides the
                            // difference.
                            Text(
                                text = when (pnlStrategy) {
                                    "Differs" -> "Differs · " +
                                        StrategyCatalog.DIFFERS_WITH_RECOVERY.risk.label
                                    "Even / Odd" -> "Even/Odd · " +
                                        StrategyCatalog.EVEN_ODD.risk.label
                                    else -> "Differs " + StrategyCatalog.DIFFERS_WITH_RECOVERY.risk.label +
                                        " · Even/Odd " + StrategyCatalog.EVEN_ODD.risk.label
                                },
                                fontSize = 11.sp,
                                color = StatRed
                            )

                            // Chart
                            val chartPoints = if (inPeriod.isNotEmpty()) {
                                var cum = 0.0
                                inPeriod.reversed().map {
                                    cum += it.simulatedNetResult
                                    cum
                                }
                            } else listOf(0.0)

                            SimpleLineChart(points = chartPoints)
                        }
                    }
                }
            }
        }

        // TAB 3: SESSIONS & DAYS
        //
        // Previously a time-of-day histogram over `positions`. It sat under a tab called
        // "Sessions & Days", reported "No sessions recorded yet" while checking trades, and could
        // not show a single session however many had been run — it never read the sessions table.
        if (selectedTab == "Sessions") {
            if (accountBlocks.isEmpty()) {
                item {
                    AccountRibbon(
                        isReal = activeAccountIsReal,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = if (activeAccountIsReal) {
                            "No live sessions recorded yet."
                        } else {
                            "No demo sessions recorded yet."
                        },
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            } else {
                accountBlocks.forEach { block ->
                    item(key = "session_section_${block.isReal}") {
                        AccountSectionHeader(
                            isReal = block.isReal,
                            tradeCount = block.settled.size,
                            netPnl = block.netPnl,
                            isCurrentAccount = block.isReal == activeAccountIsReal,
                            modifier = Modifier.padding(bottom = 8.dp, top = 4.dp)
                        )
                    }

                    if (block.sessions.isEmpty()) {
                        item(key = "session_empty_${block.isReal}") {
                            Text(
                                text = "No sessions on this account yet.",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        }
                    } else {
                        // Grouped by calendar day so the tab answers "what happened on the 14th" as
                        // well as "which run was that". Days are computed locally — the user's own
                        // midnight is the boundary that matters for a trading session, not UTC.
                        block.sessions
                            .groupBy { dayKey(it.startedAt) }
                            .toList()
                            .sortedByDescending { it.first }
                            .forEach { (day, daySessions) ->
                                item(key = "day_${block.isReal}_$day") {
                                    val dayNet = daySessions.sumOf { it.netProfit }
                                    val dayTrades = daySessions.sumOf { it.totalTrades }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = dayLabel(day),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = DemoStyle.tint(block.isReal)
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
                                        SessionRow(session = session)
                                    }
                                }
                            }
                    }
                }
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
 *
 * Where a value was never recorded — a return rate on a trade taken before the live quote was
 * wired in, or a row synced from an older build — it says "Unavailable". It is never derived from
 * numbers that were not captured, because a plausible-looking figure that nobody measured is worse
 * than an honest gap.
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
                // The sheet is the easiest thing in the app to screenshot and share, and it holds
                // the full result of one trade. Its account type is stated at the top rather than
                // left as a field further down the list.
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
                    // Null means never captured — see the KDoc.
                    value = trade.payoutRatio?.let {
                        String.format(Locale.US, "%.1f%%", it * 100.0)
                    } ?: "Unavailable",
                    tint = if (trade.payoutRatio != null) TextPrimary else TextSecondary,
                    modifier = Modifier.weight(1f)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                DetailRow("Strategy", strategyLabel)
                // Which strategy placed it is a fact the user can only read from the row's own
                // stamp, so the risk that strategy carries goes with it — a trade is never shown
                // without saying what risk it was taken on.
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
                        // A parity trade has no digit barrier: it bet "the next tick is even"
                        // (or odd) on the whole market. Saying "digit 9" would describe a bet
                        // that was never placed.
                        if (trade.targetDigit == 0) "Even class" else "Odd class"
                    } else {
                        "digit ${trade.targetDigit}"
                    }
                )
                DetailRow("Stake", String.format(Locale.US, "$%.2f", trade.simulatedStake))
                DetailRow(
                    "Price paid",
                    // Zero on records taken before the ask price was stored, which is a different
                    // fact from "paid nothing", so it reads as unavailable rather than $0.00.
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

            // Recovery context. Only shown for a recovery rung — an ordinary entry has nothing to
            // say here, and a "Step 0" row on every trade would just be noise.
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

                    // What the rung had to *win* to be worth taking, and whether it was.
                    //
                    // `outstandingBefore` alone is only half the story and reads as a repeated
                    // stake figure: it is the hole, not the cost of filling it. With the venue's
                    // own payout ratio for this contract, the stake that closes the hole *and*
                    // banks the recovery target is (L + T) / r. Both numbers are in the row's
                    // snapshot, so this is what recovery actually cost at the time rather than a
                    // figure recomputed from settings that have since moved.
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
                                "Stake placed: " + String.format(Locale.US, "$%.2f", placed) +
                                    " — clears the loss"
                            } else {
                                "Stake placed: " + String.format(Locale.US, "$%.2f", placed) +
                                    " — short by " +
                                    String.format(Locale.US, "$%.2f", needed - placed)
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
                    Text(
                        text = "A snapshot taken when the trade was placed, so it reflects what the " +
                            "ladder was doing at that moment rather than how it looks now.",
                        fontSize = 10.sp,
                        color = TextSecondary.copy(alpha = 0.75f)
                    )
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

/**
 * One session in the Sessions & Days list.
 *
 * Shows the run's own result — its trades, win/loss split and net — as stored when it closed. Those
 * figures are a snapshot, so a session that ended on a stop loss still reports the loss it actually
 * produced rather than being re-derived against whatever the ladder looks like now.
 */
@Composable
private fun SessionRow(session: SimulationSessionEntity) {
    val dayFormat = remember { SimpleDateFormat("HH:mm", Locale.US) }
    val fullFormat = remember { SimpleDateFormat("d MMM, HH:mm", Locale.US) }
    val net = session.netProfit
    val running = session.status == "ACTIVE"

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            when {
                running -> VividYellow.copy(alpha = 0.6f)
                else -> demoAwareBorder(session.isReal)
            }
        ),
        modifier = Modifier.fillMaxWidth().testTag("session_row_${session.id}")
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    // Previously a live session was labelled "LIVE" and a demo one was labelled
                    // nothing at all — the absence of a word is not a disclosure. Both now carry
                    // the same badge, so a session row can only be read one way.
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
                        // Whose run this was, stated on the row itself: a day can hold a Differs
                        // session and an Even/Odd one, and the totals below belong to both. The
                        // risk tier rides on the name so a session card never shows a strategy
                        // without its risk.
                        Text(
                            text = session.strategy + " · " +
                                StrategyCatalog.find(session.strategy).risk.tier,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = VividYellow
                        )
                    }
                    Text(
                        // "—" for a session that has not stopped yet, rather than a fake end time.
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
                        text = String.format(
                            Locale.US, "%s$%.2f", if (net >= 0) "+" else "", net
                        ),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (net >= 0) StatGreen else StatRed
                    )
                }
            }

            Text(
                text = "${session.totalTrades} trades · ${session.winCount}W / ${session.lossCount}L" +
                    " · staked ${String.format(Locale.US, "%.2f", session.totalStaked)}" +
                    " · profit ${String.format(Locale.US, "%.2f", session.grossProfit)}" +
                    " / loss ${String.format(Locale.US, "%.2f", session.grossLoss)}",
                fontSize = 11.sp,
                color = TextSecondary
            )

            // The balance arrow is only shown when the opening balance was actually captured: a
            // session that opened before the balance had loaded records 0 as its "initial", and
            // "Balance $0.00 → $2143.00" then reads as a fictional trip from nothing to the whole
            // account. The P&L above is the real figure — it is SQL over the settled trades.
            if (session.initialSimulatedBalance > 0.0) {
                Text(
                    text = "Balance ${String.format(Locale.US, "$%.2f", session.initialSimulatedBalance)}" +
                        " → ${String.format(Locale.US, "$%.2f", session.currentSimulatedBalance)}",
                    fontSize = 10.sp,
                    color = TextSecondary.copy(alpha = 0.8f)
                )
            }
            Text(
                text = "closed ${fullFormat.format(Date(session.startedAt))}" +
                    (session.endedAt?.let { " · ended ${fullFormat.format(Date(it))}" } ?: ""),
                fontSize = 10.sp,
                color = TextSecondary.copy(alpha = 0.8f)
            )
        }
    }
}

/**
 * Start of the P&L window, in epoch millis, for the selected period.
 *
 * "Today" is anchored to **local midnight**, not a trailing 24 hours — a rolling window would
 * count yesterday's afternoon as today and drop this morning. "7 Days" and "90 Days" are
 * inclusive of today, so they step back 6 and 89 days respectively.
 */
private fun periodStartMillis(period: String): Long {
    val cal = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    if (period != "Today") {
        cal.add(java.util.Calendar.DAY_OF_YEAR, -(if (period.startsWith("7")) 6 else 89))
    }
    return cal.timeInMillis
}
