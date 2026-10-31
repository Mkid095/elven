package com.tickedge.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tickedge.data.model.AccountType
import com.tickedge.data.model.SimulatedTrade
import com.tickedge.data.model.SystemConfig
import com.tickedge.data.model.TradeOutcome
import com.tickedge.data.model.TradeSettings
import com.tickedge.data.model.TradeType
import com.tickedge.domain.ActiveTradeState
import com.tickedge.domain.NextTradePreview
import com.tickedge.domain.PARITY_EVEN_SENTINEL
import com.tickedge.domain.ScanState
import com.tickedge.domain.StopNotice
import com.tickedge.domain.StrategyDefinition
import com.tickedge.domain.SimulatorState
import com.tickedge.ui.components.BalanceSyncChip
import com.tickedge.ui.components.CircularCountdown
import com.tickedge.ui.components.LiveRiskDialog
import com.tickedge.ui.theme.BorderDark
import com.tickedge.ui.theme.StatGreen
import com.tickedge.ui.theme.StatRed
import com.tickedge.ui.theme.SurfaceDarkCard
import com.tickedge.ui.theme.TextPrimary
import com.tickedge.ui.theme.TextSecondary
import com.tickedge.ui.theme.VividYellow
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun TradeScreen(
    strategy: StrategyDefinition,
    simulatorState: SimulatorState,
    activeTrade: ActiveTradeState?,
    scanState: ScanState = ScanState.Idle,
    /**
     * Why the bot halted, or null while it is running.
     *
     * Distinct from a user pressing Stop: a self-imposed halt on a risk limit is a guardrail
     * working, and the page should say so rather than looking broken.
     */
    stopNotice: StopNotice? = null,
    onDismissStopNotice: () -> Unit = {},
    nextTrade: NextTradePreview,
    consecutiveLosses: Int = 0,
    recentTradeOutcome: SimulatedTrade?,
    tradeSettings: TradeSettings,
    systemConfig: SystemConfig,
    activeAccount: com.tickedge.data.deriv.DerivAccount? = null,
    isBalanceLive: Boolean = false,
    isSyncing: Boolean = false,
    onSyncBalances: () -> Unit = {},
    realArmed: Boolean = false,
    pendingConfirmation: NextTradePreview? = null,
    onStartTrading: () -> Unit,
    onStopTrading: () -> Unit,
    onOpenTradeSettings: () -> Unit,
    onSaveSystemConfig: (SystemConfig) -> Unit,
    onNavigateToHistory: () -> Unit,
    onDismissOutcomeBanner: () -> Unit,
    onConfirmOrder: (Boolean) -> Unit = {},
    onSwitchAccount: (AccountType) -> Unit = {},
    /**
     * Whether the one-time live-account warning is on screen.
     *
     * Replaces the per-order confirmation that used to fire on every live trade. Arming live asks
     * once; after that the session places its orders without interrupting.
     */
    showLiveRiskDialog: Boolean = false,
    onAcceptLiveRisk: () -> Unit = {},
    onDeclineLiveRisk: () -> Unit = {},
    /**
     * What the balance cannot cover, checked before the user presses Start.
     *
     * Shown rather than enforced: the user may still choose to trade a base stake with no ladder
     * behind it. What matters is that the gap is stated before the first order, not discovered on
     * the rung where the loss can no longer be chased.
     */
    preflightWarning: String? = null,
    modifier: Modifier = Modifier
) {
    var showSystemConfigSheet by remember { mutableStateOf(false) }

    // Auto-dismiss the recent trade result animation after 3.5 seconds without blocking trades
    LaunchedEffect(recentTradeOutcome) {
        if (recentTradeOutcome != null) {
            delay(3500L)
            onDismissOutcomeBanner()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // The one-time live-account warning, drawn over everything. It has to be modal: arming live
        // is the moment the money changes character, and a banner the user can scroll past while
        // the bot is already placing orders is not a decision point.
        if (showLiveRiskDialog) {
            LiveRiskDialog(
                onAccept = onAcceptLiveRisk,
                onDecline = onDeclineLiveRisk,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D0E12))
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 90.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
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
                            text = "Live Trading · ${strategy.name}",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = strategy.tagline,
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButtonBox(
                            icon = Icons.Default.Tune,
                            contentDesc = "Trade Settings (Full Page)",
                            onClick = onOpenTradeSettings
                        )
                        IconButtonBox(
                            icon = Icons.Default.Settings,
                            contentDesc = "System Config",
                            onClick = { showSystemConfigSheet = true }
                        )
                    }
                }
            }

            // Why the bot stopped, when it stopped itself. Placed immediately under the header and
            // above the account card, because a bot halted on its stop loss is the single most
            // important thing on this page — and it used to simply go quiet, which is
            // indistinguishable from a crash and left the user with no idea their money had stopped
            // moving for a reason.
            stopNotice?.let { notice ->
                item {
                    StopBanner(
                        title = notice.title,
                        detail = notice.detail,
                        onDismiss = onDismissStopNotice
                    )
                }
            }

            // Balance the account cannot cover, stated before Start rather than discovered by a
            // failed order. Sits above the account card because it is about whether trading is
            // possible on that account at all.
            preflightWarning?.let { warning ->
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF2A1418), RoundedCornerShape(12.dp))
                            .border(1.dp, StatRed, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                            .testTag("preflight_warning"),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = warning,
                            fontSize = 12.sp,
                            color = TextPrimary
                        )
                    }
                }
            }

            // Account, the switch between accounts, and the position currently on the book.
            //
            // The position lives *inside* this card rather than in a card of its own because a
            // position is a property of the account it was placed on. Split across two cards it read
            // as a separate fact about the bot, and it was free to keep showing the previous
            // trade's market and digit after that trade had closed.
            item {
                val isReal = activeAccount?.kind == com.tickedge.data.deriv.DerivAccountKind.REAL
                // A contract is live. Switching accounts now would settle the next trade on a
                // different balance than the ladder was sized against, so the control is locked.
                val tradeOpen = simulatorState == SimulatorState.TRADE_IN_PROGRESS && activeTrade != null
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isReal && realArmed) StatRed else BorderDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(text = "Trading Account", fontSize = 12.sp, color = TextSecondary)
                                Text(
                                    text = (activeAccount?.id ?: "—") + "  •  " + (activeAccount?.displayName ?: "Not connected"),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                // This is the page that spends the money, so the balance and its
                                // freshness belong here too — not only on home.
                                activeAccount?.balance?.let {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.padding(top = 4.dp)
                                    ) {
                                        Text(
                                            text = String.format(
                                                java.util.Locale.US, "$%.2f", it
                                            ),
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = VividYellow
                                        )
                                        BalanceSyncChip(
                                            isLive = isBalanceLive,
                                            isSyncing = isSyncing,
                                            onSync = onSyncBalances
                                        )
                                    }
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .background(if (isReal && realArmed) StatRed else Color(0xFF1D2636), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = when {
                                        !isReal -> "DEMO"
                                        realArmed -> "LIVE ARMED"
                                        else -> "LIVE OFF"
                                    },
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isReal && realArmed) Color.White else StatGreen
                                )
                            }
                        }
                        // Account switcher.
                        //
                        // This replaces a switch that could never be turned on: it was disabled
                        // unless the *current* account was already live, which meant the only state
                        // in which it was enabled was the state you could only reach by turning it
                        // on. Selecting Live here both moves the money and arms real-money orders —
                        // one control, one meaning — and it stays locked while a contract is open.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF12141C), RoundedCornerShape(10.dp))
                                .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            listOf(AccountType.DEMO, AccountType.LIVE).forEach { type ->
                                val selected = (type == AccountType.LIVE) == realArmed
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selected) VividYellow else Color.Transparent)
                                        .clickable(enabled = !tradeOpen) { onSwitchAccount(type) }
                                        .padding(vertical = 7.dp)
                                        .testTag("account_switch_${type.name.lowercase()}"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (type == AccountType.DEMO) "Demo account" else "Live account",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = when {
                                            selected -> Color(0xFF101216)
                                            tradeOpen -> TextSecondary.copy(alpha = 0.4f)
                                            else -> TextSecondary
                                        }
                                    )
                                }
                            }
                        }

                        if (tradeOpen) {
                            Text(
                                text = "A contract is open — stop the bot before switching accounts.",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(BorderDark)
                        )

                        PositionDetail(
                            strategy = strategy,
                            activeTrade = activeTrade,
                            simulatorState = simulatorState,
                            tradeSettings = tradeSettings
                        )
                    }
                }
            }

            // What the bot is aiming at right now. Always present, so a scanning bot is visibly
            // scanning instead of showing a position card frozen at its last values.
            item {
                TargetMarketCard(scanState = scanState)
            }

            // Circular Countdown & Embedded Outcome Clock
            item {
                val isTradingActive = simulatorState == SimulatorState.TRADE_IN_PROGRESS && activeTrade != null
                val displayOutcome = if (!isTradingActive) recentTradeOutcome?.outcome else null
                val displayRecentTrade = if (!isTradingActive) recentTradeOutcome else null

                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularCountdown(
                        remainingSeconds = activeTrade?.remainingUnits ?: tradeSettings.durationValue,
                        totalSeconds = activeTrade?.totalUnits ?: tradeSettings.durationValue,
                        outcome = displayOutcome,
                        recentTrade = displayRecentTrade,
                        isTradeActive = isTradingActive
                    )
                }
            }

            // One compact card for "what the bot is doing next", replacing the two cards it used
            // to be — the recovery flow card and the next-trade card. Same information, one place,
            // so the page reads as a status rather than three overlapping dashboards.
            item {
                val nextStakeOnLoss = nextTrade.ifLossNextStake

                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Next trade",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextSecondary
                            )
                            Text(
                                text = String.format(Locale.US, "Stake $%.2f", nextTrade.stake),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }

                        Text(
                            text = when (nextTrade.tradeType) {
                                TradeType.EVEN_ODD -> {
                                    val side =
                                        if (nextTrade.targetDigit == PARITY_EVEN_SENTINEL) "Even" else "Odd"
                                    "Even / Odd ($side)" +
                                        if (nextTrade.isRecovery) ", recovery step ${nextTrade.recoveryStep}" else ""
                                }
                                TradeType.OVER_UNDER ->
                                    "Over & Under (${nextTrade.recoveryDirection} ${nextTrade.targetDigit}), " +
                                        "recovery step ${nextTrade.recoveryStep}"
                                else -> "Differs (≠ ${nextTrade.targetDigit})"
                            },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = nextTrade.marketName,
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                            Text(
                                text = if (nextTrade.livePayout)
                                    "Win $${String.format(Locale.US, "%.2f", nextTrade.ifWinNet)}"
                                else
                                    "Win per Deriv's live quote",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = StatGreen
                            )
                        }

                        Text(
                            text = "If it loses: " + nextTrade.ifLossAction + " → next stake " +
                                String.format(Locale.US, "$%.2f", nextStakeOnLoss),
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
            }

            // Start / Stop Trading Action Button
            item {
                val isTrading = simulatorState == SimulatorState.TRADE_IN_PROGRESS || simulatorState == SimulatorState.SCANNING

                Button(
                    onClick = { if (isTrading) onStopTrading() else onStartTrading() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isTrading) StatRed else VividYellow,
                        contentColor = if (isTrading) Color.White else Color(0xFF101216)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("toggle_trade_button")
                ) {
                    Icon(
                        imageVector = if (isTrading) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (isTrading) "Stop" else "Start",
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isTrading) "Stop Automated Trading" else "Start Automated Trading",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Quick Access Buttons: Trade Settings (Full Screen), System Config, and View History
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onOpenTradeSettings,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                            modifier = Modifier.weight(1f).testTag("open_trade_settings_page")
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, tint = VividYellow, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "Trade Settings", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = { showSystemConfigSheet = true },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, tint = VividYellow, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "System Config", fontSize = 12.sp)
                        }
                    }

                    // Direct shortcut to check trade history while trades run in background
                    OutlinedButton(
                        onClick = onNavigateToHistory,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                        modifier = Modifier.fillMaxWidth().testTag("view_trade_history_btn")
                    ) {
                        Icon(Icons.Default.History, contentDescription = null, tint = StatGreen, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "View Live Trade History (Runs in Background)", fontSize = 12.sp)
                    }
                }
            }
        }

        // NON-BLOCKING ANIMATED OUTCOME TOAST BANNER (Green on Win, Red on Loss)
        AnimatedVisibility(
            visible = recentTradeOutcome != null,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp, start = 16.dp, end = 16.dp)
        ) {
            recentTradeOutcome?.let { trade ->
                val isWin = trade.outcome == TradeOutcome.WON
                val pnlSign = if (isWin) "+" else ""

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isWin) Color(0xFF133624) else Color(0xFF381519),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, if (isWin) StatGreen else StatRed),
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = if (isWin) Icons.Default.CheckCircle else Icons.Default.Close,
                                contentDescription = null,
                                tint = if (isWin) StatGreen else StatRed,
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text(
                                    text = if (isWin) "Trade Won! ($pnlSign$${String.format(Locale.US, "%.2f", trade.netResult)})"
                                           else "Trade Loss (-$${String.format(Locale.US, "%.2f", trade.stake)})",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isWin) StatGreen else StatRed
                                )
                                Text(
                                    text = "${trade.marketName} • ${trade.tradeType.displayName.substringBefore(" (")} • Exit: ${trade.exitDigit}",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                        }

                        IconButton(
                            onClick = onDismissOutcomeBanner,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }

    // Real-order confirmation (per-order gate for live accounts)
    if (pendingConfirmation != null) {
        val pc = pendingConfirmation
        androidx.compose.ui.window.Dialog(onDismissRequest = { onConfirmOrder(false) }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
                border = androidx.compose.foundation.BorderStroke(1.dp, StatRed),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(text = "Confirm Real Order", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = StatRed)
                    val payoutNote = if (pc.livePayout)
                        " · live payout $" + String.format(Locale.US, "%.2f", pc.winPayout) + " (profit +$" + String.format(Locale.US, "%.2f", pc.ifWinNet)
                    else
                        " · live payout at order"
                    Text(
                        text = "Place a real order on " + (activeAccount?.id ?: "account") + ". " +
                            "Stake $" + String.format(Locale.US, "%.2f", pc.stake) + payoutNote +
                            ". " + pc.ifWinAction + ". " +
                            "If Loss: " + pc.ifLossAction + " → next stake $" + String.format(Locale.US, "%.2f", pc.ifLossNextStake) + ".",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        lineHeight = 18.sp
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(
                            onClick = { onConfirmOrder(false) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = TextSecondary)
                        ) {
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { onConfirmOrder(true) },
                            colors = ButtonDefaults.buttonColors(containerColor = StatRed, contentColor = Color.White)
                        ) {
                            Text("Place Order", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // System Config Dialog / Sheet
    if (showSystemConfigSheet) {
        SystemConfigDialog(
            initialConfig = systemConfig,
            onDismiss = { showSystemConfigSheet = false },
            onSave = { updated ->
                onSaveSystemConfig(updated)
                showSystemConfigSheet = false
            }
        )
    }
}

/**
 * The position currently on the book, inside the account card.
 *
 * Every figure is read from the open contract. There is deliberately **no fallback**: with no
 * contract open the block says so and shows nothing else. It used to fall back to the *settings*'
 * market and digit whenever [activeTrade] was null, which is how a scanning bot came to display
 * "Volatility 25 / Differs / 2" — numbers that were never a position at all, that no contract
 * carried, and that happened to match the digit the UI highlighted elsewhere, making an invented
 * position look like a confirmed one.
 *
 * When a contract *is* open, the recovery rung it sits on is stated rather than implied, because
 * the same market and digit mean different things at different rungs of the ladder.
 */
@Composable
private fun PositionDetail(
    strategy: StrategyDefinition,
    activeTrade: ActiveTradeState?,
    simulatorState: SimulatorState,
    tradeSettings: TradeSettings,
    modifier: Modifier = Modifier
) {
    val isParity = strategy.id == com.tickedge.domain.StrategyCatalog.EVEN_ODD.id

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Current Position", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = VividYellow)
            Text(
                text = "Duration: ${tradeSettings.durationValue} " +
                    "${tradeSettings.durationUnit.label.lowercase()} (1 tick = 2s)",
                fontSize = 11.sp,
                color = TextSecondary
            )
        }

        val trade = activeTrade
        if (trade == null) {
            Text(
                text = when (simulatorState) {
                    SimulatorState.SCANNING -> if (isParity) {
                        "No position open — scanning Even / Odd " +
                            "${tradeSettings.evenOddOperator.symbol} " +
                            "${tradeSettings.evenOddThreshold}% across the markets."
                    } else {
                        "No position open — scanning ${tradeSettings.comparisonOperator.symbol} " +
                            "${tradeSettings.thresholdPercent}% across the markets."
                    }
                    else -> "No position open. Press Start to begin trading."
                },
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            return@Column
        }

        val mode = when {
            isParity -> {
                val side =
                    if (trade.targetDigit == PARITY_EVEN_SENTINEL) "Even" else "Odd"
                "Even / Odd ($side)" +
                    if (trade.isRecoveryMode) " [Recovery rung ${trade.recoveryStep}]" else " [Base]"
            }
            trade.isRecoveryMode ->
                "Over & Under (${trade.recoveryDirection} ${trade.targetDigit}) [Recovery rung ${trade.recoveryStep}]"
            else -> "Differs (≠ ${trade.targetDigit}) [Base]"
        }

        TradeInfoRow(label = "Market:", value = trade.marketName)
        TradeInfoRow(
            label = "Contract Mode:",
            value = mode,
            valueColor = if (trade.isRecoveryMode) VividYellow else TextPrimary
        )
        TradeInfoRow(label = "Target Number:", value = trade.targetDigit.toString())
        TradeInfoRow(label = "Stake:", value = String.format(Locale.US, "$ %.2f", trade.currentStake))
        TradeInfoRow(label = "Status:", value = trade.statusText, valueColor = VividYellow)
    }
}

/**
 * The banner shown when the bot halts itself.
 *
 * Amber rather than red: hitting a stop loss or a daily limit is the system working as configured,
 * not a fault, and painting it as an error trains the user to ignore it. Red is reserved for the
 * blocked-order case, where something genuinely did not work.
 *
 * Deliberately not dismissible by tapping the background. It carries the reason the user's money
 * stopped moving, so it stays until they have read it or explicitly clear it — a banner that
 * vanishes on a stray scroll hides the one thing this page needs to say.
 */
@Composable
private fun StopBanner(
    title: String,
    detail: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = VividYellow
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .background(Color(0xFF2A2312), RoundedCornerShape(12.dp))
            .border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("stop_banner"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = tint
            )
            Text(
                text = detail,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = TextPrimary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        IconButtonBox(
            icon = Icons.Default.Close,
            contentDesc = "Dismiss",
            onClick = onDismiss
        )
    }
}
