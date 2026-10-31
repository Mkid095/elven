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
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tickedge.data.deriv.DerivAccount
import com.tickedge.data.local.UserProfileEntity
import com.tickedge.data.model.ConnectionStatus
import com.tickedge.ui.viewmodel.MainViewModel.LifetimeStats
import com.tickedge.ui.viewmodel.MainViewModel.SyncState
import com.tickedge.ui.components.ConnectionBadge
import com.tickedge.ui.theme.BorderDark
import com.tickedge.ui.theme.StatGreen
import com.tickedge.ui.theme.StatRed
import com.tickedge.ui.theme.SurfaceDarkCard
import com.tickedge.ui.theme.TextPrimary
import com.tickedge.ui.theme.TextSecondary
import com.tickedge.ui.theme.VividYellow
import java.util.Locale

@Composable
fun SettingsScreen(
    userProfile: UserProfileEntity,
    activeAccount: DerivAccount?,
    connectionStatus: ConnectionStatus,
    syncState: SyncState,
    /** The account's lifetime record, shown under the profile card. */
    lifetimeStats: LifetimeStats = LifetimeStats(),
    onClearHistoryClick: () -> Unit,
    onSignOutClick: () -> Unit = {},
    /** Saves the trader's display name. */
    onSaveProfileName: (String) -> Unit = {},
    /** Whether the TickEdge cloud account is signed in on this device. */
    cloudSignedIn: Boolean = false,
    /** The email the cloud account was created with, when [cloudSignedIn]. */
    cloudEmail: String? = null,
    /** Signs the device out of the TickEdge account (Deriv sign-in is kept). */
    onCloudSignOutClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showResetDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    // The name editor. Open on demand rather than always, because a permanent text field on the
    // settings page invites accidental edits.
    var showNameEditor by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf(userProfile.displayName) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D0E12))
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "Settings & Diagnostics",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }

        item { SyncStatusCard(syncState) }

        // Account Profile Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .background(Color(0xFF2C2812), CircleShape)
                                .border(1.dp, VividYellow, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = VividYellow)
                        }
                        Column {
                            // The trader's own name, with the Deriv account number underneath it.
                            //
                            // The account number is the identity the history is filed under, so it
                            // belongs on screen — and the name is what Deriv does not always give,
                            // which is why it can be edited here.
                            Text(
                                text = userProfile.displayName.ifBlank {
                                    activeAccount?.displayName ?: "Trader"
                                },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                modifier = Modifier
                                    .clickable {
                                        nameDraft = userProfile.displayName
                                        showNameEditor = true
                                    }
                                    .testTag("profile_name_toggle")
                            )
                            Text(text = activeAccount?.id ?: "Not connected", fontSize = 12.sp, color = TextSecondary)
                            Text(
                                // Same reasoning as the home card: an account that has not loaded
                                // is not an account holding zero.
                                text = activeAccount?.balance?.let {
                                    String.format(Locale.US, "Balance: $%.2f", it)
                                } ?: "Balance: loading…",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = VividYellow
                            )
                        }
                    }

                    // The name is editable because Deriv does not always give one. An account can
                    // sign in with a blank name, and the app then shows a bare account number
                    // everywhere a name would go — which reads as a bug rather than a missing
                    // field the user can fill in.
                    Text(
                        text = if (showNameEditor) "Your name" else "Tap your name to change it",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                    if (showNameEditor) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = nameDraft,
                                onValueChange = { nameDraft = it },
                                label = { Text("Display name") },
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag("profile_name_input")
                            )
                            Button(
                                onClick = {
                                    onSaveProfileName(nameDraft)
                                    showNameEditor = false
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = VividYellow,
                                    contentColor = Color(0xFF101216)
                                )
                            ) {
                                Text("Save", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // No Top Up action: this app initiates and analyses trades only. Deposits
                    // and withdrawals happen in Deriv's own client, and offering a button here
                    // implied a funding integration that does not exist.
                }
            }
        }

        // The TickEdge cloud account. Free: email in, code by email, and the settings and
        // trade history follow the email to the next phone. From Settings it can also be
        // signed out of — the Deriv sign-in and the local records stay; only the link is
        // dropped, so signing back in converges the same record again.
        item {
            CloudAccountCard(
                signedIn = cloudSignedIn,
                email = cloudEmail,
                status = syncState,
                emailText = "",
                codeText = "",
                codeSent = false,
                onEmailChange = {},
                onCodeChange = {},
                onSendCode = {},
                onVerify = {},
                onSignOut = onCloudSignOutClick,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Lifetime record for the account: total made, total lost, net, over every settled trade.
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Since you started using TickEdge",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = VividYellow
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        LifetimeFigure(
                            label = "Total profit",
                            value = lifetimeStats.totalProfit,
                            positive = true
                        )
                        LifetimeFigure(
                            label = "Total loss",
                            value = lifetimeStats.totalLoss,
                            positive = false
                        )
                        LifetimeFigure(
                            label = "Net result",
                            value = lifetimeStats.netPnl,
                            positive = null
                        )
                    }
                    Text(
                        text = if (lifetimeStats.settledTrades == 0)
                            "No settled trades yet for this account."
                        else
                            "Over ${lifetimeStats.settledTrades} settled " +
                                "${if (lifetimeStats.settledTrades == 1) "trade" else "trades"}.",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }
        }

        // Deriv Connection Telemetry
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Deriv Market-Data Service", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = VividYellow)
                        ConnectionBadge(status = connectionStatus)
                    }
                    Text(
                        text = "Endpoint: wss://api.derivws.com/trading/v1/options/ws/public",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                    Text(
                        text = "Subscribed Markets: Vol 10, Vol 25, Vol 50, Vol 75, Vol 100",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }
        }

        // Action Items
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    SettingsRowItem(
                        icon = Icons.Default.HelpOutline,
                        title = "How Digit Frequencies Work",
                        subtitle = "Statistical formulas & documentation",
                        onClick = { showHelpDialog = true }
                    )
                    SettingsDivider()
                    SettingsRowItem(
                        icon = Icons.Default.Delete,
                        title = "Reset Trade & Analysis History",
                        subtitle = "Clear local Room database records",
                        iconTint = StatRed,
                        onClick = { showResetDialog = true }
                    )
                    SettingsDivider()
                    SettingsRowItem(
                        icon = Icons.Default.Person,
                        title = "Sign Out of This Account",
                        subtitle = "Other Deriv accounts stay signed in",
                        iconTint = VividYellow,
                        onClick = onSignOutClick
                    )
                }
            }
        }

        // Risk & Compliance Disclosure Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF191B24)),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Risk Notice",
                        tint = VividYellow,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "Risk & Simulation Notice",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = VividYellow
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "TickEdge places real Deriv orders on your selected account. Demo is the default; live trading is disabled until you explicitly arm it and confirm. Historical digit frequencies do not predict future outcomes or guarantee profit. Martingale recovery can lose real funds quickly.",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        // Version & Build Info
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "TickEdge • ELVEN Trading Engine v1.0.0", fontSize = 11.sp, color = TextSecondary)
                Text(text = "Deriv API v1 (options) • Synthetic Volatility Indices", fontSize = 10.sp, color = TextSecondary.copy(alpha = 0.6f))
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(text = "Delete Trade & Analysis History", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        text = "This permanently deletes, for your signed-in account only:",
                        color = TextSecondary
                    )
                    Text(
                        text = "• Trade history on account " +
                            (activeAccount?.id ?: "unknown") +
                            (if (activeAccount?.kind == com.tickedge.data.deriv.DerivAccountKind.REAL) " (REAL)" else " (DEMO)"),
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        text = "• All recorded analysis events for your user",
                        color = TextSecondary
                    )
                    Text(
                        text = "History on your other account is kept. This cannot be undone.",
                        color = StatRed,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClearHistoryClick()
                        showResetDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatRed, contentColor = Color.White)
                ) {
                    Text(text = "Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(text = "Cancel", color = TextSecondary)
                }
            },
            containerColor = SurfaceDarkCard
        )
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text(text = "Digit Statistics Formula", color = VividYellow, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "For any digit d in 0..9:\nfrequency(d) = count(d) / valid_tick_count × 100",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Example: In a rolling window of 30 ticks, if digit 2 appears 6 times, its observed frequency is (6/30) × 100 = 20.0%.\n\nAll 10 digits always sum to exactly 100%.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showHelpDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = VividYellow, contentColor = Color(0xFF101216))
                ) {
                    Text(text = "Got it", fontWeight = FontWeight.Bold)
                }
            },
            containerColor = SurfaceDarkCard
        )
    }
}

@Composable
private fun SettingsRowItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconTint: Color = VividYellow,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            Column {
                Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text(text = subtitle, fontSize = 11.sp, color = TextSecondary)
            }
        }
        Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
    }
}

/**
 * Whether settings and trade history are actually reaching FIDScript.
 *
 * This exists because the sync state had no renderer at all: a failed save was as invisible as a
 * successful one, which is how a write path that never worked went unnoticed. `Idle` deliberately
 * says "off" rather than treating a missing session as an error — the app is fully usable with no
 * cloud session, it just will not sync.
 */
@Composable
private fun SyncStatusCard(syncState: SyncState) {
    val (label, detail, tint) = when (syncState) {
        is SyncState.Ready -> Triple(
            "Cloud sync on",
            "Settings and trade history are saved to your account.",
            StatGreen
        )
        is SyncState.Connecting -> Triple("Cloud sync connecting", "Signing in to your account...", VividYellow)
        is SyncState.Syncing -> Triple("Cloud sync syncing", "Moving settings and history to your account...", VividYellow)
        // Not grey. This state previously said "off" with no explanation and was indistinguishable
        // from a plain sign-out, so a broken sync looked like a deliberate choice. It carries the
        // reason — most often a Deriv account authorised without the `openid` scope — because
        // otherwise the user has no way to tell a configuration problem from normal operation.
        is SyncState.Unavailable -> Triple(
            "Cloud sync not connected",
            syncState.reason,
            VividYellow
        )
        is SyncState.Failed -> Triple("Cloud sync failed", syncState.reason, StatRed)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF13151D), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CloudDone,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
        Column {
            Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tint)
            Text(text = detail, fontSize = 11.sp, color = TextSecondary)
        }
    }
}

@Composable
private fun SettingsDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(BorderDark)
    )
}

/** One lifetime figure. [positive] is null when the figure is neither a profit nor a loss. */
@Composable
private fun LifetimeFigure(
    label: String,
    value: Double,
    positive: Boolean?
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = String.format(Locale.US, "$%.2f", value),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = when (positive) {
                true -> StatGreen
                false -> StatRed
                null -> if (value >= 0) StatGreen else StatRed
            }
        )
        Text(text = label, fontSize = 11.sp, color = TextSecondary)
    }
}
