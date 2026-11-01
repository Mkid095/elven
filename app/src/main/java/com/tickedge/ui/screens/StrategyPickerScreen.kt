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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tickedge.domain.StrategyDefinition
import com.tickedge.domain.StrategyRunState
import com.tickedge.ui.theme.BorderDark
import com.tickedge.ui.theme.StatGreen
import com.tickedge.ui.theme.StatRed
import com.tickedge.ui.theme.SurfaceDarkCard
import com.tickedge.ui.theme.TextPrimary
import com.tickedge.ui.theme.TextSecondary
import com.tickedge.ui.theme.VividYellow

/**
 * The strategy list — the first page after signing in.
 *
 * The app had one way of trading and no way to say so. Choosing a strategy up front makes the
 * choice explicit, gives each strategy its own menu, and leaves room for the ones that come next
 * without another rewrite.
 *
 * The live-account warning is repeated here rather than only on the Trade screen, because this is
 * where a run begins and it is the last screen before money is at stake.
 */
@Composable
fun StrategyPickerScreen(
    strategies: List<StrategyDefinition>,
    selectedStrategyId: String?,
    runStates: Map<String, StrategyRunState> = emptyMap(),
    onSelect: (StrategyDefinition) -> Unit,
    /** True when at least one strategy is trading, so the stop-all button says what it will do. */
    anyRunning: Boolean = false,
    /** Stops every running strategy at once. */
    onStopAll: () -> Unit = {},
    /** Signs the user out of every account on the device. */
    onSignOut: () -> Unit = {},
    /** The signed-in person's display name, for the header. */
    traderName: String = "Trader",
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D0E12))
            .padding(horizontal = 16.dp)
    ) {
        // The title bar. The page was flat — no brand, no identity — so it read as a settings list
        // rather than the app itself. The bar says whose app it is and gives the profile option a
        // place to live, next to the name.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            color = VividYellow
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "TickEdge",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                    Text(
                        text = traderName,
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }
            // The profile option on this page: the person signs out of every Deriv account from
            // here, not only from the settings screen the app lands on after choosing a strategy.
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(Color(0xFF232734), CircleShape)
                    .border(1.dp, BorderDark, CircleShape)
                    .clickable { onSignOut() }
                    .testTag("strategy_picker_sign_out"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "Sign out of all Deriv accounts",
                    tint = VividYellow,
                    modifier = Modifier.size(19.dp)
                )
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column {
                    Text(
                        text = "Choose a strategy",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Each strategy has its own screens and its own settings. You can " +
                            "switch at any time from Home.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }

            items(strategies.size) { index ->
                val strategy = strategies[index]
                StrategyCard(
                    strategy = strategy,
                    isSelected = strategy.id == selectedStrategyId,
                    runState = runStates[strategy.id],
                    onClick = { onSelect(strategy) }
                )
            }

            // Stop every running strategy at once. It lives on the list because that is where every
            // strategy's live state is visible at once — the stop-all belongs wherever all of them
            // are on screen, not on one strategy's own page where it would look like it only
            // stopped that one.
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1B1E28), RoundedCornerShape(12.dp))
                        .border(1.dp, if (anyRunning) StatRed else BorderDark, RoundedCornerShape(12.dp))
                        .clickable { onStopAll() }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .testTag("stop_all_trades_button"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (anyRunning) "Stop all running strategies" else "No strategies running",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (anyRunning) StatRed else TextSecondary
                    )
                    Icon(
                        imageVector = if (anyRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = if (anyRunning) StatRed else TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            item {
                // Stated here as well as on the Trade screen, because this is the last screen before a
                // run begins. A run on a live account spends real money, and the first click that can
                // cause that should be next to a sentence about it.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2A1418), RoundedCornerShape(12.dp))
                        .border(1.dp, StatRed, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "A live account spends real money. You are asked to confirm once, " +
                            "when you switch to live, and then every order in that session goes " +
                            "straight to your balance.",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun StrategyCard(
    strategy: StrategyDefinition,
    isSelected: Boolean,
    runState: StrategyRunState?,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isSelected) VividYellow else BorderDark
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("strategy_card_${strategy.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = strategy.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = if (isSelected) VividYellow else TextSecondary
                )
            }
            Text(
                text = strategy.tagline,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = VividYellow
            )
            // The risk the strategy carries, stated up front: the tier and an approximate win rate.
            Text(
                text = strategy.risk.label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = StatRed
            )
            Text(
                text = strategy.description,
                fontSize = 12.sp,
                color = TextSecondary
            )

            // What this strategy is doing right now, if it has run.
            //
            // The same figures the notification shows, so the two can never disagree: a strategy
            // that says "Scanning · 4 trades · -$1.10" here and something else in the shade would
            // make the user trust neither.
            if (runState != null && runState.trades > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1B1E28), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = runState.status.label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (runState.isRunning) VividYellow else TextSecondary
                    )
                    Text(
                        text = "This run · " +
                            "${runState.tradesLabel} · ${runState.pnlLabel}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (runState.netPnl >= 0) StatGreen else StatRed
                    )
                }
            }

            // The menu this strategy will show, so the choice is not a surprise.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                strategy.navItems.forEach { item ->
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF232734), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = item.label,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}
