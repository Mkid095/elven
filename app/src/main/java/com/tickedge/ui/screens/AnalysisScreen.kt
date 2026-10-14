package com.tickedge.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tickedge.data.model.MarketState
import com.tickedge.data.model.OverUnderStats
import com.tickedge.data.model.SupportedMarkets
import com.tickedge.domain.DigitAnalysisEngine
import com.tickedge.ui.components.ConnectionBadge
import com.tickedge.ui.components.DigitFrequencyRow
import com.tickedge.ui.theme.BorderDark
import com.tickedge.ui.theme.StatGreen
import com.tickedge.ui.theme.StatRed
import com.tickedge.ui.theme.SurfaceDarkCard
import com.tickedge.ui.theme.TextPrimary
import com.tickedge.ui.theme.TextSecondary
import com.tickedge.ui.theme.VividYellow
import java.util.Locale

/** Tab identifiers, kept as constants so the label and the branch cannot drift apart. */
private const val DIFFERS_TAB = "Differs Analysis"
private const val OVER_UNDER_TAB = "Over & Under Analysis"

/**
 * Above this, a Differs contract on [targetDigit] is on the favourable side.
 *
 * P(last digit != d) is ~90% by construction on live ticks, so anything meaningfully below that
 * means the target digit is running hot and the contract is the likelier loser. Used to colour the
 * headline and to annotate why, never to gate a trade from this screen.
 */
private const val DIFFERS_HEALTHY_THRESHOLD = 88.0

@Composable
fun AnalysisScreen(
    marketsState: Map<String, MarketState>,
    selectedSymbol: String,
    targetDigit: Int,
    inspectedDigit: Int?,
    onMarketSelected: (String) -> Unit,
    onDigitSelected: (Int) -> Unit,
    onWindowSizeChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentMarket = marketsState[selectedSymbol] ?: MarketState(
        symbol = selectedSymbol,
        displayName = "Market",
        shortName = "Market"
    )

    // Subtabs: "Differs Analysis" (Matches/Differs per digit) vs "Over & Under Analysis"
    var currentTab by remember { mutableStateOf(DIFFERS_TAB) }

    // The headline digit is whatever the user last tapped, defaulting to the configured target so
    // the screen opens on the number the bot actually trades.
    val headlineDigit = inspectedDigit ?: targetDigit

    // Toggle inside Over & Under Analysis: "All", "Over (>)", "Under (<)"
    var overUnderFilter by remember { mutableStateOf("All") }

    val maxPercentage = currentMarket.digitFrequencies.maxOfOrNull { it.percentage } ?: 10.0
    val overUnderStatsList = remember(currentMarket.ticks) {
        DigitAnalysisEngine.computeOverUnderStats(currentMarket.ticks)
    }
    val differsStatsList = remember(currentMarket.ticks) {
        DigitAnalysisEngine.computeDiffersStats(currentMarket.ticks)
    }
    val headlineDiffers = differsStatsList.firstOrNull { it.digit == headlineDigit }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D0E12))
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Screen Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Market Analysis",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Authoritative last-digit probability engine",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
                ConnectionBadge(status = currentMarket.connectionStatus)
            }
        }

        // Subtabs: Differs Analysis vs Over & Under Analysis
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDarkCard, RoundedCornerShape(12.dp))
                    .padding(4.dp)
            ) {
                listOf(DIFFERS_TAB, OVER_UNDER_TAB).forEach { tab ->
                    val isSelected = currentTab == tab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) VividYellow else Color.Transparent)
                            .clickable { currentTab = tab }
                            .padding(vertical = 9.dp)
                            .testTag("tab_${tab.replace(" ", "_").lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tab,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) Color(0xFF101216) else TextSecondary
                        )
                    }
                }
            }
        }

        // Market Chips (10, 25, 50, 75, 100)
        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(SupportedMarkets.ALL) { def ->
                    val isSelected = def.symbol == selectedSymbol
                    Surface(
                        onClick = { onMarketSelected(def.symbol) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) VividYellow else SurfaceDarkCard,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) VividYellow else BorderDark
                        ),
                        modifier = Modifier.testTag("market_chip_${def.symbol}")
                    ) {
                        Text(
                            text = def.shortName.replace("Vol ", "Volatility "),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) Color(0xFF101216) else TextPrimary,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }

        // Live Market Status & Latest Tick Quote Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = currentMarket.displayName,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Analyzing ${currentMarket.sampleSize} valid ticks (Window: ${currentMarket.configuredWindowSize})",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            val fullQuote = currentMarket.lastFormattedQuote
                            if (fullQuote.length > 1) {
                                Text(
                                    text = fullQuote.dropLast(1),
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = fullQuote.takeLast(1),
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.Black,
                                    color = VividYellow
                                )
                            } else {
                                Text(
                                    text = fullQuote,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            }
                        }
                    }

                    // Last Digit Box
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "Last Digit", fontSize = 10.sp, color = TextSecondary)
                        Spacer(modifier = Modifier.height(2.dp))
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(VividYellow, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = currentMarket.lastDigit?.toString() ?: "-",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF101216)
                            )
                        }
                    }
                }
            }
        }

        // Set Ticks to Analyze (1 to 1000)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Ticks to Analyze",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextSecondary
                        )
                        Text(
                            text = "${currentMarket.configuredWindowSize} ticks (Max 1000)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = VividYellow
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { onWindowSizeChanged((currentMarket.configuredWindowSize - 10).coerceAtLeast(1)) },
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color(0xFF232734), RoundedCornerShape(8.dp))
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Decrease", tint = TextPrimary)
                        }

                        // Presets
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(30, 50, 100, 500, 1000).forEach { preset ->
                                val isSelected = currentMarket.configuredWindowSize == preset
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSelected) VividYellow else Color(0xFF232734))
                                        .clickable { onWindowSizeChanged(preset) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$preset",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color(0xFF101216) else TextSecondary
                                    )
                                }
                            }
                        }

                        IconButton(
                            onClick = { onWindowSizeChanged((currentMarket.configuredWindowSize + 10).coerceAtMost(1000)) },
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color(0xFF232734), RoundedCornerShape(8.dp))
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Increase", tint = TextPrimary)
                        }
                    }
                }
            }
        }

        // ================= TAB 1: VOLATILITY ANALYSIS (DIGIT TICK FREQUENCIES) =================
        if (currentTab == DIFFERS_TAB) {
            // Headline: the number that actually decides a Differs contract, for the digit the
            // bot is currently configured to trade. This is the figure the entry threshold compares
            // against, so it is shown first rather than buried in a per-digit table.
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                    modifier = Modifier.fillMaxWidth().testTag("differs_headline")
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Differs from digit $headlineDigit",
                            fontSize = 13.sp,
                            color = TextSecondary,
                            fontWeight = FontWeight.SemiBold
                        )
                        val differsPct = headlineDiffers?.differsPercentage ?: 0.0
                        Text(
                            text = String.format(Locale.US, "%.1f%%", differsPct),
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (differsPct >= DIFFERS_HEALTHY_THRESHOLD) StatGreen else VividYellow
                        )
                        Text(
                            text = "${headlineDiffers?.differsCount ?: 0} of " +
                                "${currentMarket.sampleSize} ticks differ from $headlineDigit " +
                                "(target digit hit ${String.format(Locale.US, "%.1f%%", headlineDiffers?.matchPercentage ?: 0.0)} of the time)",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                        if (differsPct < DIFFERS_HEALTHY_THRESHOLD && currentMarket.sampleSize > 0) {
                            Text(
                                text = "Below ${String.format(Locale.US, "%.0f", DIFFERS_HEALTHY_THRESHOLD)}% — " +
                                    "digit $headlineDigit is running hot, so a Differs contract on it " +
                                    "is currently the riskier side.",
                                fontSize = 11.sp,
                                color = VividYellow
                            )
                        }
                        Text(
                            text = "Tap any digit below to inspect it.",
                            fontSize = 10.sp,
                            color = TextSecondary.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            item {
                Text(
                    text = "Digit Distribution",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Digit", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.width(30.dp))
                    Text(text = "Matches Bar", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.weight(1f))
                    Text(
                        text = "Match",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.width(28.dp),
                        textAlign = TextAlign.End
                    )
                    Text(
                        text = "Match %",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.width(42.dp),
                        textAlign = TextAlign.End
                    )
                    Text(
                        text = "Differs %",
                        fontSize = 12.sp,
                        color = StatGreen,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(52.dp),
                        textAlign = TextAlign.End
                    )
                    Text(text = "Trend", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.width(14.dp))
                }
            }

            // 10 Digits (0 to 9) — tapping re-targets the headline above.
            items(currentMarket.digitFrequencies) { freq ->
                DigitFrequencyRow(
                    frequency = freq,
                    isSelected = freq.digit == headlineDigit,
                    maxPercentage = maxPercentage,
                    onClick = { onDigitSelected(freq.digit) }
                )
            }

            // Summary of Most & Least Appeared Digits
            item {
                // Best/most-traded is chosen on the contract's own metric: the digit that has
                // matched least is the one a Differs contract is safest on, so that is the
                // "best" card — not the digit that has matched most.
                val mostFrequent = currentMarket.digitFrequencies.minByOrNull { it.count }
                val leastFrequent = currentMarket.digitFrequencies.maxByOrNull { it.count }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(text = "Best Differs (lowest matches)", fontSize = 11.sp, color = TextSecondary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Digit ${mostFrequent?.digit ?: "-"} · differs " +
                                        String.format(Locale.US, "%.1f%%", 100.0 - (mostFrequent?.percentage ?: 0.0)),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = StatGreen
                            )
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(text = "Worst Differs (highest matches)", fontSize = 11.sp, color = TextSecondary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Digit ${leastFrequent?.digit ?: "-"} · differs " +
                                        String.format(Locale.US, "%.1f%%", 100.0 - (leastFrequent?.percentage ?: 0.0)),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = StatRed
                            )
                        }
                    }
                }
            }
        }

        // ================= TAB 2: OVER & UNDER ANALYSIS =================
        if (currentTab == "Over & Under Analysis") {
            item {
                // Filter Switcher: All, Over, Under
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceDarkCard, RoundedCornerShape(10.dp))
                        .padding(3.dp)
                ) {
                    listOf("All" to "All View", "Over" to "Over ( > )", "Under" to "Under ( < )").forEach { (key, label) ->
                        val isSelected = overUnderFilter == key
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) VividYellow else Color.Transparent)
                                .clickable { overUnderFilter = key }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Color(0xFF101216) else TextSecondary
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Ref Digit", fontSize = 12.sp, color = TextSecondary)
                    if (overUnderFilter == "All" || overUnderFilter == "Over") {
                        Text(text = "Over (> d)", fontSize = 12.sp, color = StatGreen)
                    }
                    if (overUnderFilter == "All" || overUnderFilter == "Under") {
                        Text(text = "Under (< d)", fontSize = 12.sp, color = StatRed)
                    }
                    Text(text = "Match (= d)", fontSize = 12.sp, color = VividYellow)
                }
            }

            // Rows for each reference digit 0 through 9
            items(overUnderStatsList) { stat ->
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
                        // Digit Badge
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color(0xFF232734), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${stat.digit}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = VividYellow
                            )
                        }

                        // Over Column
                        if (overUnderFilter == "All" || overUnderFilter == "Over") {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = String.format(Locale.US, "%.1f%%", stat.overPercentage),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = StatGreen
                                )
                                Text(
                                    text = "${stat.overCount} ticks",
                                    fontSize = 10.sp,
                                    color = TextSecondary
                                )
                            }
                        }

                        // Under Column
                        if (overUnderFilter == "All" || overUnderFilter == "Under") {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = String.format(Locale.US, "%.1f%%", stat.underPercentage),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = StatRed
                                )
                                Text(
                                    text = "${stat.underCount} ticks",
                                    fontSize = 10.sp,
                                    color = TextSecondary
                                )
                            }
                        }

                        // Match Column
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = String.format(Locale.US, "%.1f%%", stat.matchPercentage),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = "${stat.matchCount} ticks",
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
