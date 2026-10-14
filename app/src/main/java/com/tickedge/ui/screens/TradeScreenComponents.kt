package com.tickedge.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.tickedge.data.model.SystemConfig
import com.tickedge.ui.theme.BorderDark
import com.tickedge.ui.theme.StatRed
import com.tickedge.ui.theme.SurfaceDarkCard
import com.tickedge.ui.theme.TextPrimary
import com.tickedge.ui.theme.TextSecondary
import com.tickedge.ui.theme.VividYellow

/** A label/value pair used in the trade summary panels. */
@Composable
fun TradeInfoRow(label: String, value: String, valueColor: Color = TextPrimary) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 13.sp, color = TextSecondary)
        Text(text = value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}

/** Square tappable icon button matching the panel surface treatment. */
@Composable
fun IconButtonBox(
    icon: ImageVector,
    contentDesc: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(SurfaceDarkCard, RoundedCornerShape(10.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDesc,
            tint = VividYellow,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * Editor for the safety/system configuration applied to automated runs: market rotation,
 * per-market trade caps, the daily loss limit, and whether hitting that limit halts trading.
 */
@Composable
fun SystemConfigDialog(
    initialConfig: SystemConfig,
    onDismiss: () -> Unit,
    onSave: (SystemConfig) -> Unit
) {
    var randomMarkets by remember { mutableStateOf(initialConfig.randomMarkets) }
    var maxTrades by remember { mutableStateOf("${initialConfig.maxTradesPerMarket}") }
    var maxDailyLoss by remember { mutableStateOf("${initialConfig.maxDailyLoss}") }
    var stopOnLimit by remember { mutableStateOf(initialConfig.stopTradingOnLimit) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
            border = BorderStroke(1.dp, BorderDark),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "System Configuration",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = VividYellow
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Random Markets Switch",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Execute trades randomly across 5 synthetic indices",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = randomMarkets,
                        onCheckedChange = { randomMarkets = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF101216),
                            checkedTrackColor = VividYellow
                        )
                    )
                }

                if (!randomMarkets) {
                    OutlinedTextField(
                        value = maxTrades,
                        onValueChange = { maxTrades = it },
                        label = { Text("Max Trades per Market before Switching") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = VividYellow,
                            unfocusedBorderColor = BorderDark,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = maxDailyLoss,
                    onValueChange = { maxDailyLoss = it },
                    label = { Text("Daily Stop Loss Limit ($)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StatRed,
                        unfocusedBorderColor = BorderDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Stop Trading Automatically upon Limit",
                        fontSize = 13.sp,
                        color = TextPrimary
                    )
                    Switch(
                        checked = stopOnLimit,
                        onCheckedChange = { stopOnLimit = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF101216),
                            checkedTrackColor = VividYellow
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = TextSecondary
                        )
                    ) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val parsedTrades = maxTrades.toIntOrNull() ?: initialConfig.maxTradesPerMarket
                            val parsedLoss = maxDailyLoss.toDoubleOrNull() ?: initialConfig.maxDailyLoss
                            onSave(
                                SystemConfig(
                                    randomMarkets = randomMarkets,
                                    maxTradesPerMarket = parsedTrades,
                                    maxDailyLoss = parsedLoss,
                                    stopTradingOnLimit = stopOnLimit
                                )
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = VividYellow,
                            contentColor = Color(0xFF101216)
                        )
                    ) {
                        Text("Save System Config", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}