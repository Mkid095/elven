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
import androidx.compose.runtime.LaunchedEffect
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
import com.tickedge.domain.StrategyCatalog
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
private const val EVEN_ODD_TAB = "Even/Odd"

/**
 * Analysis screen, gated by the active strategy.
 *
 * Differs shows its own two tabs — Differs and Over/Under (Over/Under belongs to Differs as its
 * recovery analysis). Even/Odd shows only the Even/Odd tab: the digits 0-9, each labelled
 * even/odd, and a tapped digit's per-market breakdown across all five markets.
 */
@Composable
fun AnalysisScreen(
    marketsState: Map<String, MarketState>,
    selectedSymbol: String,
    inspectedDigit: Int?,
    strategyId: String,
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

    val isEvenOddStrategy = strategyId == StrategyCatalog.EVEN_ODD.id
    val tabs = if (isEvenOddStrategy) {
        listOf(EVEN_ODD_TAB)
    } else {
        listOf(DIFFERS_TAB, OVER_UNDER_TAB)
    }

    // Subtabs: which analysis this strategy offers. The default is the strategy's first tab.
    var currentTab by remember(strategyId) {
        mutableStateOf(if (isEvenOddStrategy) EVEN_ODD_TAB else DIFFERS_TAB)
    }
    LaunchedEffect(strategyId) { currentTab = tabs.first() }

    // Which row of the distribution table is highlighted — **only** what the user tapped.
    //
    // It used to fall back to `tradeSettings.targetDigit`, which meant a digit was highlighted
    // before the user had chosen anything. That digit was a *default setting*, not a decision, and
    // in random mode — the default — the engine picks a fresh digit for every trade and targets
    // nothing in particular. A permanently highlighted digit therefore read as "this is what we are
    // trading", on a screen that has nothing to do with what is about to be traded. Nothing is
    // highlighted until it is asked for.
    val selectedDigit = inspectedDigit

    // Toggle inside Over & Under Analysis: "All", "Over (>)", "Under (<)"
    var overUnderFilter by remember { mutableStateOf("All") }

    val maxPercentage = currentMarket.digitFrequencies.maxOfOrNull { it.percentage } ?: 10.0
    val overUnderStatsList = remember(currentMarket.ticks) {
        DigitAnalysisEngine.computeOverUnderStats(currentMarket.ticks)
    }

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

        // Subtabs: only the analysis the active strategy offers.
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDarkCard, RoundedCornerShape(12.dp))
                    .padding(4.dp)
            ) {
                tabs.forEach { tab ->
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

        // ================= DIFFERS ANALYSIS (DIGIT DISTRIBUTION) — Differs strategy only =================
        //
        // No single-digit headline: the table below already carries a Differs % column for all ten
        // digits, so a banner for one of them repeated a row rather than adding anything.
        if (!isEvenOddStrategy && currentTab == DIFFERS_TAB) {
            item {
                Text(
                    text = "Digit Distribution",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary
                )
            }

            item {
                // The header mirrors [DigitFrequencyRow] column by column: same widths, same
                // spacers, same order. Previously it had its own numbers, so the two disagreed
                // and the last two columns — the ones with the longest labels — wrapped onto
                // two lines.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(30.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Bar",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Count",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        modifier = Modifier.width(28.dp),
                        textAlign = TextAlign.End
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Match%",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        modifier = Modifier.width(42.dp),
                        textAlign = TextAlign.End
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Differs%",
                        fontSize = 11.sp,
                        color = StatGreen,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(52.dp),
                        textAlign = TextAlign.End
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(modifier = Modifier.width(14.dp).height(14.dp))
                }
            }

            // 10 Digits (0 to 9) — tapping re-targets the headline above.
            items(currentMarket.digitFrequencies) { freq ->
                DigitFrequencyRow(
                    frequency = freq,
                    isSelected = freq.digit == selectedDigit,
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

        // ================= EVEN / ODD ANALYSIS — Even/Odd strategy only =================
        //
        // Numbers are what is observed; even/odd is what the numbers add up to. So the view is
        // number-first:
        // 1. Every number 0-9 individually — how often THAT number ended the last tick in this
        //    market's window (% and count).
        // 2. The roll-up — how much the even numbers (0,2,4,6,8) accounted for in total, and how
        //    much the odd numbers (1,3,5,7,9) did. That pair is what the strategy bets on: the
        //    next tick's digit being even or odd on this market.
        // All numbers are for the market selected in the chips above, over its own window set in
        // the "Ticks to Analyze" control.
        if (isEvenOddStrategy && currentTab == EVEN_ODD_TAB) {
            val evenPct = DigitAnalysisEngine.evenPercentFor(currentMarket.digitFrequencies)
            val oddPct = DigitAnalysisEngine.oddPercentFor(currentMarket.digitFrequencies)
            val hotIsEven = evenPct >= oddPct

            // 1 — Every number, individually.
            item {
                Text(
                    text = "Each number · ${currentMarket.shortName}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary
                )
            }

            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    (0..9).forEach { d ->
                        val isSelected = d == selectedDigit
                        val f = currentMarket.digitFrequencies.firstOrNull { it.digit == d }
                        val hasData = currentMarket.sampleSize >= 10
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) Color(0xFF2C2812) else SurfaceDarkCard)
                                .clickable { onDigitSelected(d) }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag("even_odd_digit_${d}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .background(
                                        if (isSelected) VividYellow else Color(0xFF232734),
                                        RoundedCornerShape(6.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$d",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = if (isSelected) Color(0xFF101216) else VividYellow
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (d % 2 == 0) "Even number" else "Odd number",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (d % 2 == 0) StatGreen else StatRed
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = if (hasData) {
                                    String.format(
                                        Locale.US,
                                        "%.1f%% · %d of %d ticks",
                                        f?.percentage ?: 0.0,
                                        f?.count ?: 0,
                                        currentMarket.sampleSize
                                    )
                                } else {
                                    "no data yet"
                                },
                                fontSize = 11.sp,
                                color = if (hasData) TextPrimary else TextSecondary
                            )
                        }
                    }
                }
            }

            // 2 — The roll-up: what the even numbers and the odd numbers together make up.
            item {
                Text(
                    text = "Roll-up · even numbers vs odd numbers",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ParityCard(
                        title = "Even numbers, combined",
                        percentage = evenPct,
                        sampleSize = currentMarket.sampleSize,
                        isHot = hotIsEven
                    )
                    ParityCard(
                        title = "Odd numbers, combined",
                        percentage = oddPct,
                        sampleSize = currentMarket.sampleSize,
                        isHot = !hotIsEven
                    )
                }
            }

            item {
                Text(
                    text = "Even and odd are not numbers themselves: the even % is what the " +
                        "numbers 0,2,4,6,8 made up in total, and the odd % what 1,3,5,7,9 did. " +
                        "The strategy bets on that roll-up — that the next tick's digit on this " +
                        "market will be even or odd. \"Above\" bets the hot side; \"below\" the " +
                        "cold one. All figures describe the window already counted, not a " +
                        "prediction of the next tick.",
                    fontSize = 10.sp,
                    color = TextSecondary.copy(alpha = 0.75f)
                )
            }
        }

        // ================= OVER & UNDER ANALYSIS — belongs to the Differs strategy =================
        if (!isEvenOddStrategy && currentTab == OVER_UNDER_TAB) {
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

/**
 * One side of the parity readout: which class of last digit the market has been producing, over
 * the window already counted.
 *
 * The two cards always sum to 100% — they are the two halves of the same distribution — so the
 * "hot" side is whichever one is at or above 50%. That is the side an "above" condition would
 * trade, and the screen says so with the border rather than leaving the user to do the subtraction.
 */
@Composable
private fun ParityCard(
    title: String,
    percentage: Double,
    sampleSize: Int,
    isHot: Boolean,
    modifier: Modifier = Modifier
) {
    val tint = if (isHot) StatGreen else TextSecondary
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isHot) StatGreen.copy(alpha = 0.5f) else BorderDark
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = title, fontSize = 11.sp, color = TextSecondary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = String.format(Locale.US, "%.1f%%", percentage),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = tint
            )
            Text(
                text = "${if (isHot) "hot side · " else ""}$sampleSize ticks",
                fontSize = 10.sp,
                color = TextSecondary
            )
        }
    }
}
