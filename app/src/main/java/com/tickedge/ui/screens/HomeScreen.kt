package com.tickedge.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tickedge.data.deriv.DerivAccount
import com.tickedge.data.local.UserProfileEntity
import com.tickedge.data.model.AccountType
import com.tickedge.data.model.ConnectionStatus
import com.tickedge.data.model.MarketState
import com.tickedge.data.model.SupportedMarkets
import com.tickedge.ui.components.ConnectionBadge
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
fun HomeScreen(
    userProfile: UserProfileEntity,
    activeAccount: DerivAccount?,
    activeAccountType: AccountType,
    statsPeriod: String, // "Today" or "All Time"
    marketsState: Map<String, MarketState>,
    overallConnection: ConnectionStatus,
    todayPnL: Double,
    allTimePnL: Double,
    winRate: Int,
    totalTrades: Int,
    onSwitchAccountType: (AccountType) -> Unit,
    onSwitchStatsPeriod: (String) -> Unit,
    onMarketSelected: (String) -> Unit,
    onNavigateToTrade: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayBalance = activeAccount?.balance ?: 0.0

    val displayPnL = if (statsPeriod == "Today") todayPnL else allTimePnL

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D0E12))
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Header: Branding & Connection Status
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(VividYellow, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "E",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF101216)
                        )
                    }
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "ELVEN",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp,
                                color = VividYellow
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "TickEdge",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                        Text(
                            text = "Deriv Synthetic Market Bot",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }

                ConnectionBadge(status = overallConnection)
            }
        }

        // Hello Trader & Balance Card with Live / Demo Account Switcher
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    // Header greeting & Avatar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = activeAccount?.displayName ?: "Trader",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "This is your balance",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }

                        // Account Type Pill Switcher (Demo / Live)
                        Row(
                            modifier = Modifier
                                .background(Color(0xFF12141C), RoundedCornerShape(12.dp))
                                .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
                                .padding(3.dp)
                        ) {
                            listOf(AccountType.DEMO, AccountType.LIVE).forEach { type ->
                                val isSelected = activeAccountType == type
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(if (isSelected) VividYellow else Color.Transparent)
                                        .clickable { onSwitchAccountType(type) }
                                        .padding(horizontal = 12.dp, vertical = 5.dp)
                                        .testTag("account_switch_${type.name.lowercase()}"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (type == AccountType.DEMO) "Demo" else "Live",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color(0xFF101216) else TextSecondary
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Balance Display with Live Sync indicator (NO deposit / withdraw buttons)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = activeAccountType.displayName,
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                                Text(
                                    text = activeAccount?.id?.let { "($it)" } ?: "Connecting…",
                                    fontSize = 10.sp,
                                    color = VividYellow
                                )
                            }
                            Text(
                                text = String.format(Locale.US, "$ %.2f", displayBalance),
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Black,
                                color = VividYellow
                            )
                        }

                        // Syncing status badge
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier
                                .background(Color(0xFF151822), RoundedCornerShape(10.dp))
                                .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = "Status",
                                tint = if (overallConnection == ConnectionStatus.LIVE) StatGreen else VividYellow,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = overallConnection.label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Profit & Stats Toggle (Today's P/L vs All Time P/L)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF13151D), RoundedCornerShape(12.dp))
                            .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
                            .padding(vertical = 12.dp, horizontal = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // P/L with Toggle
                        Column(
                            modifier = Modifier
                                .clickable {
                                    val next = if (statsPeriod == "Today") "All Time" else "Today"
                                    onSwitchStatsPeriod(next)
                                }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "$statsPeriod P/L",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Toggle Period",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(10.dp)
                                )
                            }
                            val pnlSign = if (displayPnL >= 0) "+" else ""
                            Text(
                                text = String.format(Locale.US, "%s$%.2f", pnlSign, displayPnL),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (displayPnL >= 0) StatGreen else StatRed
                            )
                        }

                        Box(modifier = Modifier.width(1.dp).height(30.dp).background(BorderDark))

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "Win Rate", fontSize = 11.sp, color = TextSecondary)
                            Text(
                                text = "$winRate%",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }

                        Box(modifier = Modifier.width(1.dp).height(30.dp).background(BorderDark))

                        Column(horizontalAlignment = Alignment.End) {
                            Text(text = "Total Trades", fontSize = 11.sp, color = TextSecondary)
                            Text(
                                text = "$totalTrades",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }
        }

        // Section Title: 5 Markets Showing Last Ticks
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Synthetic Markets (Last Ticks)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Tap market for full analysis",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }

        // 5 Market Cards: Showing Last Tick Details ONLY (no premature digit probability)
        SupportedMarkets.ALL.forEach { def ->
            item(key = def.symbol) {
                val state = marketsState[def.symbol] ?: MarketState(
                    symbol = def.symbol,
                    displayName = def.displayName,
                    shortName = def.shortName,
                    pipSize = def.pipSize
                )
                LastTickMarketCard(
                    marketState = state,
                    onClick = { onMarketSelected(def.symbol) },
                    modifier = Modifier.fillMaxWidth().testTag("market_card_${def.symbol}")
                )
            }
        }

        // Quick Launch Trading Action
        item {
            Button(
                onClick = onNavigateToTrade,
                colors = ButtonDefaults.buttonColors(
                    containerColor = VividYellow,
                    contentColor = Color(0xFF101216)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("start_trading_button")
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Start",
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Launch Trading",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun LastTickMarketCard(
    marketState: MarketState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    val timeString = if (marketState.lastEpoch != null && marketState.lastEpoch > 0) {
        timeFormat.format(Date(marketState.lastEpoch * 1000L))
    } else {
        "Live Feed"
    }

    Surface(
        onClick = onClick,
        color = SurfaceDarkCard,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Market Name & Time of last tick
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = marketState.displayName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(
                                if (marketState.connectionStatus == ConnectionStatus.LIVE) StatGreen else StatRed,
                                CircleShape
                            )
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Last Tick: $timeString",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                    Text(
                        text = "• ${marketState.pipSize} decimals",
                        fontSize = 11.sp,
                        color = TextSecondary.copy(alpha = 0.7f)
                    )
                }
            }

            // Current Quote & Highlighted Last Digit Box
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Price quote with directional change icon
                Column(horizontalAlignment = Alignment.End) {
                    val fullQuote = marketState.lastFormattedQuote
                    if (fullQuote.length > 1) {
                        Text(
                            text = fullQuote.dropLast(1),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                    } else {
                        Text(
                            text = fullQuote,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                    }

                    // Price diff arrow
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (marketState.lastPriceDiff > 0) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Up", tint = StatGreen, modifier = Modifier.size(12.dp))
                            Text(text = "Tick Up", fontSize = 10.sp, color = StatGreen)
                        } else if (marketState.lastPriceDiff < 0) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = "Down", tint = StatRed, modifier = Modifier.size(12.dp))
                            Text(text = "Tick Down", fontSize = 10.sp, color = StatRed)
                        } else {
                            Icon(Icons.Default.Remove, contentDescription = "Neutral", tint = TextSecondary, modifier = Modifier.size(12.dp))
                            Text(text = "Unchanged", fontSize = 10.sp, color = TextSecondary)
                        }
                    }
                }

                // Authoritative Last Digit Highlight Box
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(VividYellow, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = marketState.lastDigit?.toString() ?: "-",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF101216)
                    )
                }
            }
        }
    }
}
