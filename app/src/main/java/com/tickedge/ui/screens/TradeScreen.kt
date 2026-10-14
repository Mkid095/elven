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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import com.tickedge.data.model.SimulatedTrade
import com.tickedge.data.model.SystemConfig
import com.tickedge.data.model.TradeOutcome
import com.tickedge.data.model.TradeSettings
import com.tickedge.data.model.TradeType
import com.tickedge.domain.ActiveTradeState
import com.tickedge.domain.NextTradePreview
import com.tickedge.domain.ScanState
import com.tickedge.domain.SimulatorState
import com.tickedge.ui.components.CircularCountdown
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
    simulatorState: SimulatorState,
    activeTrade: ActiveTradeState?,
    scanState: ScanState = ScanState.Idle,
    nextTrade: NextTradePreview,
    consecutiveLosses: Int = 0,
    recentTradeOutcome: SimulatedTrade?,
    tradeSettings: TradeSettings,
    systemConfig: SystemConfig,
    activeAccount: com.tickedge.data.deriv.DerivAccount? = null,
    realArmed: Boolean = false,
    pendingConfirmation: NextTradePreview? = null,
    onStartTrading: () -> Unit,
    onStopTrading: () -> Unit,
    onOpenTradeSettings: () -> Unit,
    onSaveSystemConfig: (SystemConfig) -> Unit,
    onNavigateToHistory: () -> Unit,
    onDismissOutcomeBanner: () -> Unit,
    onConfirmOrder: (Boolean) -> Unit = {},
    onToggleRealArmed: (Boolean) -> Unit = {},
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
                            text = "Live Trading",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Paper-Trading Simulator (Automated Execution)",
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

            // Account & real-trading safety
            item {
                val isReal = activeAccount?.kind == com.tickedge.data.deriv.DerivAccountKind.REAL
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Allow real-money orders (live account)",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                            Switch(
                                checked = realArmed,
                                onCheckedChange = onToggleRealArmed,
                                enabled = isReal,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF101216),
                                    checkedTrackColor = StatRed
                                )
                            )
                        }
                    }
                }
            }

            // What the bot is aiming at right now. Always present, so a scanning bot is visibly
            // scanning instead of showing a position card frozen at its last values.
            item {
                TargetMarketCard(scanState = scanState)
            }

            // Current Trade Live Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Current Position",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = VividYellow
                            )
                            Text(
                                text = "Duration: ${tradeSettings.durationValue} ${tradeSettings.durationUnit.label.lowercase()} (1 tick = 2s)",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        val isRecovery = activeTrade?.isRecoveryMode == true || consecutiveLosses > 0
                        val displayMarket = activeTrade?.marketName ?: "Volatility 25"
                        val displayType = if (isRecovery) {
                            "Over & Under (${activeTrade?.recoveryDirection ?: tradeSettings.recoveryDirection} ${activeTrade?.targetDigit ?: tradeSettings.recoveryDigit}) [Recovery Step $consecutiveLosses]"
                        } else {
                            "Differs (≠ ${activeTrade?.targetDigit ?: tradeSettings.targetDigit}) [Base]"
                        }
                        val displayDigit = activeTrade?.targetDigit ?: (if (isRecovery) tradeSettings.recoveryDigit else tradeSettings.targetDigit)
                        val displayStake = activeTrade?.currentStake ?: (if (isRecovery) tradeSettings.stakeAmount * Math.pow(2.0, consecutiveLosses.toDouble()) else tradeSettings.stakeAmount)
                        val displayStatus = if (simulatorState == SimulatorState.TRADE_IN_PROGRESS) {
                            activeTrade?.statusText ?: "Active"
                        } else if (simulatorState == SimulatorState.SCANNING) {
                            "Scanning condition (${tradeSettings.comparisonOperator.symbol} ${tradeSettings.thresholdPercent}%)..."
                        } else {
                            "Bot Idle (Ready to start)"
                        }

                        TradeInfoRow(label = "Market:", value = displayMarket)
                        TradeInfoRow(label = "Contract Mode:", value = displayType, valueColor = if (isRecovery) VividYellow else TextPrimary)
                        TradeInfoRow(label = "Target Number:", value = "$displayDigit")
                        TradeInfoRow(label = "Simulated Stake:", value = String.format(Locale.US, "$ %.2f", displayStake))
                        TradeInfoRow(
                            label = "Status:",
                            value = displayStatus,
                            valueColor = if (simulatorState == SimulatorState.TRADE_IN_PROGRESS) VividYellow else TextSecondary
                        )
                    }
                }
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

            // DYNAMIC MARTINGALE RECOVERY FLOW CARD
            item {
                val isCurrentlyRecovery = activeTrade?.isRecoveryMode == true || consecutiveLosses > 0
                val activeStake = activeTrade?.currentStake ?: (if (isCurrentlyRecovery) tradeSettings.stakeAmount * Math.pow(2.0, consecutiveLosses.toDouble()) else tradeSettings.stakeAmount)
                val nextStakeOnLoss = activeStake * 2.0

                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isCurrentlyRecovery) VividYellow else BorderDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = null,
                                    tint = VividYellow,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Martingale Dynamic Flow",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = VividYellow
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .background(if (isCurrentlyRecovery) VividYellow else Color(0xFF1D2636), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (isCurrentlyRecovery) "IN RECOVERY (STEP $consecutiveLosses)" else "BASE MODE (DIFFERS)",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isCurrentlyRecovery) Color(0xFF101216) else StatGreen
                                )
                            }
                        }

                        // Flow Outcome 1: IF WIN
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0F2618), RoundedCornerShape(10.dp))
                                .border(1.dp, StatGreen, RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "✓ IF WIN:",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StatGreen
                                    )
                                    Text(
                                        text = if (isCurrentlyRecovery) {
                                            "ROLL BACK TO DIFFERS"
                                        } else {
                                            "CONTINUES ON BASE DIFFERS"
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = VividYellow
                                    )
                                }
                                Text(
                                    text = if (isCurrentlyRecovery) {
                                        "Win Payout: $${String.format(Locale.US, "%.2f", activeStake * 1.95)} (Net profit: +$${String.format(Locale.US, "%.2f", activeStake * 0.95)}). All previous losses restored! Next contract rolls back to base Differs stake ($${String.format(Locale.US, "%.2f", tradeSettings.stakeAmount)})."
                                    } else {
                                        "Win Payout: $${String.format(Locale.US, "%.2f", activeStake * 1.10)} (Net profit: +$${String.format(Locale.US, "%.2f", activeStake * 0.10)}). Proceeds with safe base Differs."
                                    },
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    color = Color(0xFFD4E6D9)
                                )
                            }
                        }

                        // Flow Outcome 2: IF LOSS
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF281316), RoundedCornerShape(10.dp))
                                .border(1.dp, StatRed, RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "✗ IF LOSS:",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StatRed
                                    )
                                    Text(
                                        text = if (isCurrentlyRecovery) {
                                            "NEXT STAKE: $${String.format(Locale.US, "%.2f", nextStakeOnLoss)} (STEP ${consecutiveLosses + 1})"
                                        } else {
                                            "NEXT STAKE: $${String.format(Locale.US, "%.2f", nextStakeOnLoss)} (SWITCHES TO RECOVERY)"
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = VividYellow
                                    )
                                }
                                Text(
                                    text = if (isCurrentlyRecovery) {
                                        "Continues Martingale on ${tradeSettings.recoveryDirection} ${tradeSettings.recoveryDigit} with double stake ($${String.format(Locale.US, "%.2f", nextStakeOnLoss)}) to guarantee full capital recovery on next win."
                                    } else {
                                        "Differs loss detected. Automator switches immediately to Over & Under (${tradeSettings.recoveryDirection} ${tradeSettings.recoveryDigit}) with 2x stake ($${String.format(Locale.US, "%.2f", nextStakeOnLoss)}) to quickly recoup the lost stake."
                                    },
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    color = Color(0xFFEBC7CB)
                                )
                            }
                        }
                    }
                }
            }

            // Next Trade Preview Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141720)),
                    shape = RoundedCornerShape(14.dp),
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
                                text = "Next Trade Setup",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextSecondary
                            )
                            Text(
                                text = String.format(Locale.US, "Stake: $%.2f", nextTrade.stake),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = VividYellow
                            )
                        }

                        Text(
                            text = if (nextTrade.isRecovery) {
                                "Over & Under (${nextTrade.recoveryDirection} ${nextTrade.targetDigit}) [Recovery Step ${nextTrade.recoveryStep}]"
                            } else {
                                "Differs (≠ ${nextTrade.targetDigit}) [Base]"
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
                                text = "Market: ${nextTrade.marketName}",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                            Text(
                                text = "Potential Win: +$${String.format(Locale.US, "%.2f", nextTrade.ifWinNet)}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = StatGreen
                            )
                        }

                        Text(
                            text = "If Loss: ${nextTrade.ifLossAction} ($${String.format(Locale.US, "%.2f", nextTrade.ifLossNextStake)})",
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
                    Text(
                        text = "Place a real order on " + (activeAccount?.id ?: "account") + ". " +
                            "Stake $" + String.format(Locale.US, "%.2f", pc.stake) + ". " + pc.ifWinAction + ". " +
                            "If Loss: " + pc.ifLossAction + ".",
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
