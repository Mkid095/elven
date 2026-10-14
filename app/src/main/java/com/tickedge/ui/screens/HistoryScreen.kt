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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tickedge.data.local.SimulationPositionEntity
import com.tickedge.ui.components.SimpleLineChart
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

@Composable
fun HistoryScreen(
    positions: List<SimulationPositionEntity>,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf("Trades") } // "Trades", "P&L", "Sessions"
    var typeFilter by remember { mutableStateOf("All") } // "All", "Differs", "Over & Under"
    var pnlPeriod by remember { mutableStateOf("Today") } // "Today", "7 Days", "30 Days"

    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.US) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D0E12))
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
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
                // Filter chips: All, Differs, Over & Under
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("All", "Differs", "Over & Under").forEach { f ->
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

            // Table header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Time", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.width(48.dp))
                    Text(text = "Market", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.width(60.dp))
                    Text(text = "Type", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.width(64.dp))
                    Text(text = "P/L", fontSize = 12.sp, color = TextSecondary)
                }
            }

            val filteredPositions = positions.filter {
                if (typeFilter == "All") true else it.scenarioType.contains(typeFilter, ignoreCase = true)
            }

            if (filteredPositions.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .background(SurfaceDarkCard, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "No trades recorded yet", color = TextSecondary, fontSize = 13.sp)
                    }
                }
            } else {
                items(filteredPositions) { pos ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = timeFormatter.format(Date(pos.settledAt)),
                                fontSize = 12.sp,
                                color = TextSecondary,
                                modifier = Modifier.width(48.dp)
                            )
                            Text(
                                text = pos.marketName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                modifier = Modifier.width(60.dp)
                            )
                            Text(
                                text = if (pos.scenarioType.contains("Differs")) "Differs" else "O/U",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                modifier = Modifier.width(64.dp)
                            )
                            val isWin = pos.outcome == "WON"
                            val pnlSign = if (isWin) "+" else ""
                            Text(
                                text = String.format(Locale.US, "%s$%.2f", pnlSign, pos.simulatedNetResult),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isWin) StatGreen else StatRed
                            )
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

            item {
                // Period filtering. Previously every chip rendered identical numbers because
                // this block summed the unfiltered `positions` list.
                val windowStart = periodStartMillis(pnlPeriod)
                val inPeriod = positions.filter { it.settledAt >= windowStart }

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

                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(text = "Total P/L", fontSize = 12.sp, color = TextSecondary)
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

                        // Chart
                        val chartPoints = if (positions.isNotEmpty()) {
                            var cum = 0.0
                            positions.reversed().map {
                                cum += it.simulatedNetResult
                                cum
                            }
                        } else listOf(0.0)

                        SimpleLineChart(points = chartPoints)
                    }
                }
            }
        }

        // TAB 3: SESSIONS & DAYS
        if (selectedTab == "Sessions") {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(text = "Time Buckets Distribution", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = VividYellow)

                        if (positions.isEmpty()) {
                            Text(text = "No sessions recorded yet", fontSize = 12.sp, color = TextSecondary)
                        } else {
                            val cal = java.util.Calendar.getInstance()
                            val bucketOf: (Long) -> Int = { ts ->
                                cal.timeInMillis = ts
                                when (val h = cal.get(java.util.Calendar.HOUR_OF_DAY)) {
                                    in 0..5 -> 0
                                    in 6..11 -> 1
                                    in 12..17 -> 2
                                    else -> 3
                                }
                            }
                            val labels = listOf("00:00 - 06:00", "06:00 - 12:00", "12:00 - 18:00", "18:00 - 24:00")
                            val counts = IntArray(4)
                            val nets = DoubleArray(4)
                            positions.forEach { val b = bucketOf(it.settledAt); counts[b]++; nets[b] += it.simulatedNetResult }
                            labels.forEachIndexed { i, label ->
                                val sign = if (nets[i] >= 0) "+" else ""
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF13151D), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = label, fontSize = 12.sp, color = TextSecondary)
                                    Text(text = "${counts[i]} trades", fontSize = 12.sp, color = TextPrimary)
                                    Text(
                                        text = String.format(Locale.US, "%s$%.2f", sign, nets[i]),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (nets[i] >= 0) StatGreen else StatRed
                                    )
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
