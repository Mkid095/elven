package com.tickedge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tickedge.ui.screens.AnalysisScreen
import com.tickedge.ui.screens.AuthScreen
import com.tickedge.ui.screens.HistoryScreen
import com.tickedge.ui.screens.HomeScreen
import com.tickedge.ui.screens.SettingsScreen
import com.tickedge.ui.screens.TradeScreen
import com.tickedge.ui.screens.TradeSettingsScreen
import com.tickedge.ui.theme.BorderDark
import com.tickedge.ui.theme.TextSecondary
import com.tickedge.ui.theme.TickEdgeTheme
import com.tickedge.ui.theme.VividYellow
import com.tickedge.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by lazy {
        ViewModelProvider(this)[MainViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TickEdgeTheme {
                TickEdgeApp(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAuthState()
    }
}

data class NavItem(
    val route: String,
    val title: String,
    val icon: ImageVector
)

@Composable
fun TickEdgeApp(
    viewModel: MainViewModel = viewModel()
) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity

    val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val activeAccountType by viewModel.activeAccountType.collectAsStateWithLifecycle()
    val statsPeriod by viewModel.statsPeriod.collectAsStateWithLifecycle()

    val marketsState by viewModel.marketsState.collectAsStateWithLifecycle()
    val selectedSymbol by viewModel.selectedMarketSymbol.collectAsStateWithLifecycle()
    val overallConnection by viewModel.overallConnection.collectAsStateWithLifecycle()
    val tradeSettings by viewModel.tradeSettings.collectAsStateWithLifecycle()
    val systemConfig by viewModel.systemConfig.collectAsStateWithLifecycle()
    val inspectedDigit by viewModel.inspectedDigit.collectAsStateWithLifecycle()

    val simulatorState by viewModel.simulatorState.collectAsStateWithLifecycle()
    val activeTrade by viewModel.activeTrade.collectAsStateWithLifecycle()
    val nextTrade by viewModel.nextTrade.collectAsStateWithLifecycle()
    val consecutiveLosses by viewModel.consecutiveLosses.collectAsStateWithLifecycle()
    val recentTradeOutcome by viewModel.recentTradeOutcome.collectAsStateWithLifecycle()
    val pastPositions by viewModel.pastPositions.collectAsStateWithLifecycle()
    val activeAccount by viewModel.activeAccount.collectAsStateWithLifecycle()
    val realArmed by viewModel.realArmed.collectAsStateWithLifecycle()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsStateWithLifecycle()
    val activeSession by viewModel.activeSession.collectAsStateWithLifecycle()
    val sessionSummary by viewModel.sessionSummary.collectAsStateWithLifecycle()
    val lastConfirmedTrade by viewModel.lastConfirmedTrade.collectAsStateWithLifecycle()

    // Gate the authenticated experience on the real auth state
    if (authState !is com.tickedge.auth.AuthState.Authenticated) {
        AuthScreen(
            authState = authState,
            onContinueWithDeriv = { activity?.let { viewModel.launchDerivAuth(it) } }
        )
        return
    }

    // Back handlers
    BackHandler(enabled = currentScreen == "trade_settings") {
        viewModel.navigateTo("trade")
    }
    BackHandler(enabled = currentScreen != "home" && currentScreen != "trade_settings") {
        viewModel.navigateTo("home")
    }

    val navItems = listOf(
        NavItem("home", "Home", Icons.Default.Home),
        NavItem("analysis", "Analysis", Icons.Default.Analytics),
        NavItem("trade", "Trade", Icons.Default.TrendingUp),
        NavItem("history", "History", Icons.Default.History),
        NavItem("settings", "Settings", Icons.Default.Settings)
    )

    // Calculate PnL & Win Rate from real settled records (no fabricated defaults)
    val dayAgo = System.currentTimeMillis() - 86_400_000L
    val allTimePnl = pastPositions.sumOf { it.simulatedNetResult }
    val todayPnl = pastPositions.filter { it.settledAt >= dayAgo }.sumOf { it.simulatedNetResult }
    val settledCount = pastPositions.count { it.outcome == "WON" || it.outcome == "LOST" }
    val winCount = pastPositions.count { it.outcome == "WON" }
    val winRate = if (settledCount > 0) winCount * 100 / settledCount else 0
    val totalTrades = pastPositions.size

    // Hide bottom navigation when in full-page trade settings
    val showBottomBar = currentScreen != "trade_settings"

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF0D0E12),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = Color(0xFF12141C),
                    tonalElevation = 0.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderDark, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                ) {
                    navItems.forEach { item ->
                        val isSelected = currentScreen == item.route
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = { viewModel.navigateTo(item.route) },
                            icon = {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.title,
                                    modifier = Modifier.size(22.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = item.title,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF101216),
                                selectedTextColor = VividYellow,
                                indicatorColor = VividYellow,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary
                            ),
                            modifier = Modifier.testTag("nav_${item.route}")
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentScreen) {
                "home" -> HomeScreen(
                    userProfile = userProfile,
                    activeAccount = activeAccount,
                    activeAccountType = activeAccountType,
                    statsPeriod = statsPeriod,
                    marketsState = marketsState,
                    overallConnection = overallConnection,
                    todayPnL = todayPnl,
                    allTimePnL = allTimePnl,
                    winRate = winRate,
                    totalTrades = totalTrades,
                    onSwitchAccountType = { viewModel.switchAccountType(it) },
                    onSwitchStatsPeriod = { viewModel.switchStatsPeriod(it) },
                    onMarketSelected = { symbol ->
                        viewModel.selectMarket(symbol)
                        viewModel.navigateTo("analysis")
                    },
                    onNavigateToTrade = { viewModel.navigateTo("trade") }
                )
                "analysis" -> AnalysisScreen(
                    marketsState = marketsState,
                    selectedSymbol = selectedSymbol,
                    targetDigit = tradeSettings.targetDigit,
                    inspectedDigit = inspectedDigit,
                    onMarketSelected = { viewModel.selectMarket(it) },
                    onDigitSelected = { viewModel.setInspectedDigit(it) },
                    onWindowSizeChanged = { viewModel.setWindowSize(it) }
                )
                "trade" -> TradeScreen(
                    simulatorState = simulatorState,
                    activeTrade = activeTrade,
                    nextTrade = nextTrade,
                    consecutiveLosses = consecutiveLosses,
                    recentTradeOutcome = recentTradeOutcome,
                    tradeSettings = tradeSettings,
                    systemConfig = systemConfig,
                    activeAccount = activeAccount,
                    realArmed = realArmed,
                    pendingConfirmation = pendingConfirmation,
                    onStartTrading = { viewModel.startTrading() },
                    onStopTrading = { viewModel.stopTrading() },
                    onOpenTradeSettings = { viewModel.navigateTo("trade_settings") },
                    onSaveSystemConfig = { viewModel.updateSystemConfig(it) },
                    onNavigateToHistory = { viewModel.navigateTo("history") },
                    onDismissOutcomeBanner = { viewModel.dismissOutcomeBanner() },
                    onConfirmOrder = { viewModel.resolveConfirmation(it) },
                    onToggleRealArmed = { viewModel.setRealArmed(it) }
                )
                "trade_settings" -> TradeSettingsScreen(
                    currentSettings = tradeSettings,
                    onBackClick = { viewModel.navigateTo("trade") },
                    onSaveSettings = { updated ->
                        viewModel.updateTradeSettings(updated)
                    }
                )
                "history" -> HistoryScreen(
                    positions = pastPositions,
                    activeSession = activeSession,
                    sessionSummary = sessionSummary,
                    lastConfirmedTrade = lastConfirmedTrade
                )
                "settings" -> SettingsScreen(
                    userProfile = userProfile,
                    activeAccount = activeAccount,
                    connectionStatus = overallConnection,
                    onClearHistoryClick = { viewModel.clearHistory() },
                    onSignOutClick = { viewModel.derivLogout() }
                )
            }
        }
    }
}
