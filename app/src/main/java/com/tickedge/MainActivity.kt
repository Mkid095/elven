package com.tickedge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tickedge.data.model.AccountType
import com.tickedge.ui.screens.AnalysisScreen
import com.tickedge.ui.screens.AuthScreen
import com.tickedge.ui.screens.HistoryScreen
import com.tickedge.ui.screens.HomeScreen
import com.tickedge.ui.screens.SettingsScreen
import com.tickedge.ui.screens.StrategyPickerScreen
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
        // The trading socket is torn down when the app backgrounds, so anything that moved while
        // we were away — including trades placed from another device — is only picked up here.
        viewModel.refreshAccounts()
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
    val context = LocalContext.current

    // The running-trading notification.
    //
    // Android 13 requires POST_NOTIFICATIONS before anything can appear in the shade. Without it
    // the bot still runs in the background — the foreground service still keeps the process alive
    // — but the user is told nothing, which for a bot spending their money is the worst of both
    // worlds: it keeps trading silently.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // A refusal is not treated as an error. The bot still trades; the user simply does not get
        // the running indicator. Blocking trading on it would take away the app's main function
        // over a cosmetic setting.
        if (!granted) {
            android.util.Log.i("MainActivity", "Notifications declined — trading still runs, " +
                "but the running indicator will not be shown")
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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
    val isBalanceLive by viewModel.isBalanceLive.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val realArmed by viewModel.realArmed.collectAsStateWithLifecycle()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsStateWithLifecycle()
    val activeSession by viewModel.activeSession.collectAsStateWithLifecycle()
    // Every session this user has run, for the Sessions & Days tab.
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    // History is scoped to the account in use, so switching demo/live switches the record with it.
    val accountPositions by viewModel.accountPositions.collectAsStateWithLifecycle()
    val accountSessions by viewModel.accountSessions.collectAsStateWithLifecycle()
    val sessionSummary by viewModel.sessionSummary.collectAsStateWithLifecycle()
    val lastConfirmedTrade by viewModel.lastConfirmedTrade.collectAsStateWithLifecycle()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    // Why the bot halted by itself. Null while it is running, and after the user pressed Stop.
    val stopNotice by viewModel.stopNotice.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    // The TickEdge cloud account: whether this device is signed in, and whose email it is.
    val cloudSignedIn by viewModel.cloudSignedIn.collectAsStateWithLifecycle()
    val cloudEmail by viewModel.cloudEmail.collectAsStateWithLifecycle()
    // The one-time warning that replaces a per-order confirmation on live trades.
    val liveRiskDialog by viewModel.liveRiskDialog.collectAsStateWithLifecycle()
    // What the current balance cannot cover, checked before the user presses Start.
    val preflightWarning by viewModel.preflightWarning.collectAsStateWithLifecycle()

    // Gate the authenticated experience on the real auth state
    if (authState !is com.tickedge.auth.AuthState.Authenticated) {
        AuthScreen(
            authState = authState,
            onContinueWithDeriv = { activity?.let { viewModel.launchDerivAuth(it) } },
            cloudSignedIn = cloudSignedIn,
            cloudEmail = cloudEmail,
            cloudStatus = syncState,
            onSendMagicCode = { viewModel.sendCloudMagicCode(it) },
            onVerifyCode = { email, code -> viewModel.verifyCloudMagicCode(email, code) },
            onSignUpVerify = { first, last, user, email, code ->
                viewModel.signUpCloudAccount(first, last, user, email, code)
            },
            onCloudSignOut = { viewModel.signOutCloud() }
        )
        return
    }

    // The strategy the user is running. Everything about the menu comes from it.
    //
    // The bar used to be a hard-coded list, so "the app" and "the strategy" were the same thing
    // and a second strategy would have needed a second app. Each strategy now names its own
    // routes, and the icons are resolved from a route → icon table — a strategy that has no
    // Analysis tab simply does not list one.
    val strategy by viewModel.selectedStrategy.collectAsStateWithLifecycle()

    // Back handlers
    BackHandler(enabled = currentScreen == "trade_settings") {
        viewModel.navigateTo("trade")
    }
    // Back from the strategy list returns to the app rather than leaving it — the user arrived
    // here from inside, and backing out of the app entirely would look like the list is a dead end.
    BackHandler(enabled = currentScreen == "strategies") {
        viewModel.navigateTo(strategy.defaultRoute)
    }
    BackHandler(enabled = currentScreen != "home" && currentScreen != "trade_settings" && currentScreen != "strategies") {
        viewModel.navigateTo("home")
    }

    val navItems = listOf(
        NavItem("home", "Home", Icons.Default.Home),
        NavItem("analysis", "Analysis", Icons.Default.Analytics),
        NavItem("trade", "Trade", Icons.Default.TrendingUp),
        NavItem("history", "History", Icons.Default.History),
        NavItem("settings", "Settings", Icons.Default.Settings)
    )

    val visibleNavItems = strategy.navItems.mapNotNull { item ->
        navItems.firstOrNull { it.route == item.route }?.copy(title = item.label)
    }

    // The lifetime figures come from SQL over the whole record, and the trade count from a
    // COUNT query — not from the positions list, which is a slice. That is what lets "all time"
    // keep counting past whatever the list holds.
    val lifetime by viewModel.lifetimeStats.collectAsStateWithLifecycle()
    val tradeCount by viewModel.tradeCount.collectAsStateWithLifecycle()

    // Calculate PnL & Win Rate from real settled records (no fabricated defaults)
    val dayAgo = System.currentTimeMillis() - 86_400_000L
    val allTimePnl = lifetime.netPnl
    val todayPnl = pastPositions.filter { it.settledAt >= dayAgo }.sumOf { it.simulatedNetResult }
    val winRate = if (lifetime.settledTrades > 0) lifetime.winCount * 100 / lifetime.settledTrades else 0
    val totalTrades = tradeCount

    // Hide bottom navigation when in full-page trade settings, and on the strategy list — which is
    // a page above the app rather than a page within it, so it carries no menu of its own.
    val showBottomBar = currentScreen != "trade_settings" && currentScreen != "strategies"

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
                        // Only the current strategy's own routes are shown.
                        if (visibleNavItems.none { it.route == item.route }) return@forEach
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
                // The strategy list — the first page after signing in, and where the app says
                // what it can do.
                "strategies" -> StrategyPickerScreen(
                    strategies = viewModel.strategies,
                    selectedStrategyId = viewModel.selectedStrategyId.collectAsStateWithLifecycle().value,
                    // What each strategy has done so far, so the list says which ones are running
                    // and what they have made — the same figures the notification carries.
                    runStates = viewModel.runStates.collectAsStateWithLifecycle().value,
                    onSelect = { viewModel.selectStrategy(it) },
                    anyRunning = viewModel.runStates.collectAsStateWithLifecycle().value.values.any { it.isRunning },
                    onStopAll = { viewModel.stopAllTrading() },
                    onSignOut = { viewModel.derivLogout() },
                    traderName = userProfile.displayName.ifBlank { activeAccount?.displayName ?: "Trader" }
                )
                "home" -> HomeScreen(
                    userProfile = userProfile,
                    activeAccount = activeAccount,
                    activeAccountType = activeAccountType,
                    isBalanceLive = isBalanceLive,
                    isSyncing = isSyncing,
                    onSyncBalances = viewModel::syncBalances,
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
                    onNavigateToTrade = { viewModel.navigateTo("trade") },
                    // The running strategy's live figures, so the bar says what it has done —
                    // the same numbers the notification carries.
                    strategyRunState = viewModel.runStates.collectAsStateWithLifecycle().value[strategy.id],
                    strategy = strategy,
                    onOpenStrategies = { viewModel.openStrategyPicker() },
                    // The Deriv account manager: which account is signed in, and how to change it.
                    derivSlots = viewModel.derivSlots.collectAsStateWithLifecycle().value,
                    activeSlotId = viewModel.activeSlotId.collectAsStateWithLifecycle().value,
                    onSelectDerivAccount = viewModel::switchDerivAccount,
                    onAddDerivAccount = {
                        activity?.let { viewModel.addDerivAccount(it) }
                    },
                    onSignOutDerivAccount = viewModel::signOutDerivAccount
                )
                "analysis" -> AnalysisScreen(
                    marketsState = marketsState,
                    selectedSymbol = selectedSymbol,
                    inspectedDigit = inspectedDigit,
                    strategyId = strategy.id,
                    onMarketSelected = { viewModel.selectMarket(it) },
                    onDigitSelected = { viewModel.setInspectedDigit(it) },
                    onWindowSizeChanged = { viewModel.setWindowSize(it) }
                )
                "trade" -> TradeScreen(
                    strategy = strategy,
                    isBalanceLive = isBalanceLive,
                    isSyncing = isSyncing,
                    onSyncBalances = viewModel::syncBalances,
                    simulatorState = simulatorState,
                    activeTrade = activeTrade,
                    scanState = scanState,
                    stopNotice = stopNotice,
                    onDismissStopNotice = viewModel::dismissStopNotice,
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
                    onSwitchAccount = { viewModel.setRealArmed(it == AccountType.LIVE) },
                    showLiveRiskDialog = liveRiskDialog,
                    onAcceptLiveRisk = viewModel::acceptLiveRisk,
                    onDeclineLiveRisk = viewModel::declineLiveRisk,
                    preflightWarning = preflightWarning
                )
                "trade_settings" -> TradeSettingsScreen(
                    strategy = strategy,
                    currentSettings = tradeSettings,
                    onBackClick = { viewModel.navigateTo("trade") },
                    onSaveSettings = { updated ->
                        viewModel.updateTradeSettings(updated)
                    }
                )
                "history" -> HistoryScreen(
                    // Both accounts' records, not just the one in use. The screen partitions them
                    // into separate blocks — live above demo, each with its own totals and border —
                    // so every record stays visible without ever being summed together.
                    positions = pastPositions,
                    sessions = sessions,
                    activeSession = activeSession,
                    sessionSummary = sessionSummary,
                    lastConfirmedTrade = lastConfirmedTrade,
                    activeAccountIsReal = activeAccountType == AccountType.LIVE
                )
                "settings" -> SettingsScreen(
                    userProfile = userProfile,
                    activeAccount = activeAccount,
                    connectionStatus = overallConnection,
                    syncState = syncState,
                    // The account's lifetime record — total profit, total loss, net — since first use.
                    lifetimeStats = viewModel.lifetimeStats.collectAsStateWithLifecycle().value,
                    onResetStatsClick = { viewModel.resetStats() },
                    onResetHistoryClick = { viewModel.resetAllHistory() },
                    canResetHistory = cloudSignedIn,
                    scheduledRuns = viewModel.scheduledRuns.collectAsStateWithLifecycle().value,
                    onAddSchedule = viewModel::saveScheduledRun,
                    onToggleSchedule = viewModel::setScheduledRunEnabled,
                    onDeleteSchedule = viewModel::deleteScheduledRun,
                    batteryExempt = viewModel.isBatteryOptimizationsDisabled(),
                    onRequestBatteryExemption = {
                        // The exemption screen is a system activity; it has to be launched from a real
                        // Activity, which is the one hosting this composition, not the composition scope.
                        activity?.let { viewModel.requestBatteryExemption(it) }
                    },
                    onSignOutClick = { viewModel.derivLogout() },
                    onSaveProfileName = viewModel::saveProfileDisplayName,
                    cloudSignedIn = cloudSignedIn,
                    cloudEmail = cloudEmail,
                    onCloudSignOutClick = { viewModel.signOutCloud() }
                )
            }
        }
    }
}
