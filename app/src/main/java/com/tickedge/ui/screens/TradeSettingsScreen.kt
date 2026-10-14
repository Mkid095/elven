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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tickedge.data.model.AnalysisMetric
import com.tickedge.data.model.ComparisonOperator
import com.tickedge.data.model.TradeDurationUnit
import com.tickedge.data.model.TradeSettings
import com.tickedge.ui.theme.BorderDark
import com.tickedge.ui.theme.StatGreen
import com.tickedge.ui.theme.StatRed
import com.tickedge.ui.theme.SurfaceDarkCard
import com.tickedge.ui.theme.TextPrimary
import com.tickedge.ui.theme.TextSecondary
import com.tickedge.ui.theme.VividYellow
import java.util.Locale

/** Smallest analysis window accepted. Below the minimum-sample size no entry can fire. */
private const val MIN_ANALYSIS_WINDOW = 1

/** Largest analysis window; matches the retained-history cap in the websocket service. */
private const val MAX_ANALYSIS_WINDOW = 1000

/**
 * Ticks required before a threshold is considered meaningful.
 *
 * A window shorter than this can never satisfy an entry, because the minimum-sample guard rejects
 * the evaluation first. Kept in step with `MIN_ANALYSIS_SAMPLE` in MainViewModel.
 */
private const val MIN_ANALYSIS_SAMPLE = 10

@Composable
fun TradeSettingsScreen(
    currentSettings: TradeSettings,
    onBackClick: () -> Unit,
    onSaveSettings: (TradeSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    var targetDigit by remember { mutableStateOf(currentSettings.targetDigit) }
    var recoveryDigit by remember { mutableStateOf(currentSettings.recoveryDigit) }
    var recoveryDirection by remember { mutableStateOf(currentSettings.recoveryDirection) }
    var operator by remember { mutableStateOf(currentSettings.comparisonOperator) }
    var thresholdText by remember { mutableStateOf("${currentSettings.thresholdPercent}") }
    var analysisMetric by remember { mutableStateOf(currentSettings.analysisMetric) }
    var analysisWindowText by remember { mutableStateOf("${currentSettings.analysisWindowSize}") }
    var stakeText by remember { mutableStateOf(String.format(Locale.US, "%.2f", currentSettings.stakeAmount)) }
    var takeProfitText by remember { mutableStateOf(String.format(Locale.US, "%.2f", currentSettings.takeProfit)) }
    var stopLossText by remember { mutableStateOf(String.format(Locale.US, "%.2f", currentSettings.stopLoss)) }
    var durationUnit by remember { mutableStateOf(currentSettings.durationUnit) }
    var durationValueText by remember { mutableStateOf("${currentSettings.durationValue}") }
    var martingale by remember { mutableStateOf(currentSettings.martingaleEnabled) }

    // Parse live numeric values for real-time table calculation
    val parsedStake = stakeText.toDoubleOrNull() ?: 1.00

    val parsedThreshold = thresholdText.toDoubleOrNull() ?: currentSettings.thresholdPercent
    val parsedWindow = analysisWindowText.toIntOrNull() ?: currentSettings.analysisWindowSize

    // Compute winning digits for recovery
    val winningDigits = if (recoveryDirection.equals("OVER", ignoreCase = true)) {
        ((recoveryDigit + 1)..9).toList()
    } else {
        (0 until recoveryDigit).toList()
    }
    val losingDigits = (0..9).filter { !winningDigits.contains(it) }

    val winningProb = winningDigits.size / 10.0
    val overUnderPayoutMultiplier = if (winningDigits.isNotEmpty()) {
        ((1.0 - winningProb) / winningProb * 0.95).coerceIn(0.20, 5.0)
    } else {
        0.95
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D0E12))
    ) {
        // Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.testTag("settings_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = VividYellow
                )
            }
            Text(
                text = "Trade Settings Configuration",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: Target Number (Digit 0-9) for Differs
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Target Number for Differs (Digit 0–9)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = VividYellow
                        )
                        Text(
                            text = "Base trade contract wins if exit tick last digit differs from this selected number.",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            (0..9).forEach { d ->
                                val isSelected = targetDigit == d
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(42.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) VividYellow else Color(0xFF232734))
                                        .clickable { targetDigit = d },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$d",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color(0xFF101216) else TextPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Section 2: Entry Condition (Above / Below & Threshold %) with Clear Explanation
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Entry Condition (Above / Below)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = VividYellow
                        )

                        // Condition selector pills
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            listOf(
                                ComparisonOperator.GREATER_THAN to "Enter Above (>)",
                                ComparisonOperator.LESS_THAN to "Enter Below (<)"
                            ).forEach { (op, label) ->
                                val isSel = operator == op
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isSel) VividYellow else Color(0xFF232734))
                                        .clickable { operator = op }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSel) Color(0xFF101216) else TextPrimary
                                    )
                                }
                            }
                        }

                        // Custom Threshold Percentage Text Field
                        OutlinedTextField(
                            value = thresholdText,
                            onValueChange = { thresholdText = it },
                            label = { Text("Threshold Percentage (%)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            trailingIcon = { Text("%", color = VividYellow, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = VividYellow,
                                unfocusedBorderColor = BorderDark,
                                focusedLabelColor = VividYellow,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedContainerColor = Color(0xFF13151D),
                                unfocusedContainerColor = Color(0xFF13151D)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().testTag("custom_threshold_input")
                        )

                        AnalysisConditionFields(
                            targetDigit = targetDigit,
                            metric = analysisMetric,
                            onMetricChange = { analysisMetric = it },
                            operator = operator,
                            thresholdPercent = parsedThreshold,
                            windowText = analysisWindowText,
                            onWindowTextChange = { analysisWindowText = it },
                            minWindow = MIN_ANALYSIS_WINDOW,
                            maxWindow = MAX_ANALYSIS_WINDOW,
                            minSampleSize = MIN_ANALYSIS_SAMPLE
                        )

                        // Explanatory Banner: WHY we have Target Digit and Threshold Above/Below
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF171A24), RoundedCornerShape(10.dp))
                                .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
                                .padding(12.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lightbulb,
                                    contentDescription = "Why this condition",
                                    tint = VividYellow,
                                    modifier = Modifier.size(18.dp)
                                )
                                Column {
                                    Text(
                                        text = "Why do we set this condition?",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = VividYellow
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "We monitor Digit $targetDigit. When its empirical frequency in the rolling tick window shifts ${if (operator == ComparisonOperator.GREATER_THAN) "ABOVE" else "BELOW"} $thresholdText%, it indicates a statistical imbalance. The bot executes contracts anticipating mean reversion or continuation.",
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Section 3: Trade Duration (Custom entry in Ticks, Seconds, Minutes)
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Trade Duration",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = VividYellow
                        )
                        Text(
                            text = "1 tick is calibrated to 2 seconds",
                            fontSize = 11.sp,
                            color = StatGreen
                        )

                        // Unit Selector (Ticks, Seconds, Minutes)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TradeDurationUnit.values().forEach { unit ->
                                val isSel = durationUnit == unit
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSel) VividYellow else Color(0xFF232734))
                                        .clickable { durationUnit = unit }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = unit.label,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSel) Color(0xFF101216) else TextPrimary
                                    )
                                }
                            }
                        }

                        // Custom Duration Numeric Text Field
                        OutlinedTextField(
                            value = durationValueText,
                            onValueChange = { durationValueText = it },
                            label = { Text("Custom Duration (${durationUnit.label})") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            trailingIcon = { Text(durationUnit.shortLabel, color = TextSecondary, modifier = Modifier.padding(end = 12.dp)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = VividYellow,
                                unfocusedBorderColor = BorderDark,
                                focusedLabelColor = VividYellow,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedContainerColor = Color(0xFF13151D),
                                unfocusedContainerColor = Color(0xFF13151D)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().testTag("custom_duration_input")
                        )
                    }
                }
            }

            // Section 4: Custom Stake, Take Profit, and Stop Loss
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Stake & Risk Management",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = VividYellow
                        )

                        // Custom Stake Amount
                        OutlinedTextField(
                            value = stakeText,
                            onValueChange = { stakeText = it },
                            label = { Text("Custom Stake Amount ($)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            leadingIcon = { Text("$", color = VividYellow, fontWeight = FontWeight.Bold) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = VividYellow,
                                unfocusedBorderColor = BorderDark,
                                focusedLabelColor = VividYellow,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedContainerColor = Color(0xFF13151D),
                                unfocusedContainerColor = Color(0xFF13151D)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().testTag("custom_stake_input")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Custom Take Profit
                            OutlinedTextField(
                                value = takeProfitText,
                                onValueChange = { takeProfitText = it },
                                label = { Text("Take Profit ($)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                leadingIcon = { Text("$", color = StatGreen, fontWeight = FontWeight.Bold) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = StatGreen,
                                    unfocusedBorderColor = BorderDark,
                                    focusedLabelColor = StatGreen,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedContainerColor = Color(0xFF13151D),
                                    unfocusedContainerColor = Color(0xFF13151D)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f).testTag("custom_tp_input")
                            )

                            // Custom Stop Loss
                            OutlinedTextField(
                                value = stopLossText,
                                onValueChange = { stopLossText = it },
                                label = { Text("Stop Loss ($)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                leadingIcon = { Text("$", color = StatRed, fontWeight = FontWeight.Bold) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = StatRed,
                                    unfocusedBorderColor = BorderDark,
                                    focusedLabelColor = StatRed,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedContainerColor = Color(0xFF13151D),
                                    unfocusedContainerColor = Color(0xFF13151D)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f).testTag("custom_sl_input")
                            )
                        }
                    }
                }
            }

            // Section 5: Martingale Recovery Toggle
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Martingale Recovery System",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "When active, any loss on Differs automatically switches to Over & Under recovery with double stake to restore capital.",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                            Switch(
                                checked = martingale,
                                onCheckedChange = { martingale = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF101216),
                                    checkedTrackColor = VividYellow
                                )
                            )
                        }
                    }
                }
            }

            // Section 6: AUTOMATED RECOVERY SETUP (OVER & UNDER DIGIT AND DIRECTION)
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (martingale) VividYellow else BorderDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Automated Recovery Setup (Over & Under)",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = VividYellow
                            )
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFF26200A), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (martingale) "ACTIVE" else "STANDBY",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (martingale) VividYellow else TextSecondary
                                )
                            }
                        }

                        Text(
                            text = "Configure the exact contract used to recover previous Differs losses. Choose whether you enter with OVER or UNDER, and set the target recovery digit.",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )

                        // Over / Under Direction Selector
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Recovery Entry Direction:",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                listOf("OVER", "UNDER").forEach { dir ->
                                    val isSel = recoveryDirection.equals(dir, ignoreCase = true)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isSel) VividYellow else Color(0xFF232734))
                                            .clickable { recoveryDirection = dir }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (dir == "OVER") "Enter OVER (>)" else "Enter UNDER (<)",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSel) Color(0xFF101216) else TextPrimary
                                        )
                                    }
                                }
                            }
                        }

                        // Target Number for Over & Under (Digit 0-9)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Recovery Target Number ($recoveryDirection $recoveryDigit):",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                (0..9).forEach { d ->
                                    val isSelected = recoveryDigit == d
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(42.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSelected) VividYellow else Color(0xFF232734))
                                            .clickable { recoveryDigit = d },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "$d",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) Color(0xFF101216) else TextPrimary
                                        )
                                    }
                                }
                            }
                        }

                        // Why switch to Over & Under explanation box
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF151922), RoundedCornerShape(10.dp))
                                .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
                                .padding(12.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Shield,
                                        contentDescription = null,
                                        tint = VividYellow,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Why automated switching to Over & Under?",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = VividYellow
                                    )
                                }
                                Text(
                                    text = "Differs contracts pay a conservative ~10% profit ($0.10 on $1). If a rare Differs loss occurs, recovering through Differs alone requires 10 consecutive wins. TickEdge automatically pivots to $recoveryDirection $recoveryDigit with high ~${String.format(Locale.US, "%.0f", overUnderPayoutMultiplier * 100)}% payout so a single winning recovery trade instantly restores all previous losses and returns you to base Differs.",
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                    color = TextSecondary
                                )
                            }
                        }

                        // Winning & Losing Digits Breakdown with exact Dollar amounts
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Winning Digits & Payout Distribution:",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )

                            // Winning numbers pill row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF0F2618), RoundedCornerShape(8.dp))
                                    .border(1.dp, StatGreen, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "WINNING NUMBERS: ${winningDigits.joinToString(", ")}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StatGreen
                                    )
                                    Text(
                                        text = "${winningDigits.size} digits (${String.format(Locale.US, "%.1f", winningProb * 100)}% win rate)",
                                        fontSize = 11.sp,
                                        color = TextSecondary
                                    )
                                }
                                val recoveryStakeExample = parsedStake * 2.0
                                val winPayoutExample = recoveryStakeExample * (1.0 + overUnderPayoutMultiplier)
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "Payout: $${String.format(Locale.US, "%.2f", winPayoutExample)}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StatGreen
                                    )
                                    Text(
                                        text = "Net: +$${String.format(Locale.US, "%.2f", winPayoutExample - recoveryStakeExample)}",
                                        fontSize = 10.sp,
                                        color = TextSecondary
                                    )
                                }
                            }

                            // Losing numbers pill row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF281316), RoundedCornerShape(8.dp))
                                    .border(1.dp, StatRed, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "LOSING NUMBERS: ${losingDigits.joinToString(", ")}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StatRed
                                    )
                                    Text(
                                        text = "${losingDigits.size} digits (${String.format(Locale.US, "%.1f", (1.0 - winningProb) * 100)}% loss rate)",
                                        fontSize = 11.sp,
                                        color = TextSecondary
                                    )
                                }
                                Text(
                                    text = "Triggers Next Step",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = StatRed
                                )
                            }
                        }
                    }
                }
            }

            // Section 7: RECOVERY & RISK TABLE (MARTINGALE PROGRESSION SCHEDULE)
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDarkCard),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
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
                            Text(
                                text = "Martingale Recovery & Risk Table",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = VividYellow
                            )
                            Icon(
                                imageVector = Icons.Default.TrendingUp,
                                contentDescription = null,
                                tint = VividYellow,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Text(
                            text = "Live progression schedule based on current stake ($${String.format(Locale.US, "%.2f", parsedStake)}) and recovery setting ($recoveryDirection $recoveryDigit):",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        // Step 1: Base Differs
                        RecoveryRiskRow(
                            stepNumber = 1,
                            contractType = "Base Differs (≠ $targetDigit)",
                            stake = parsedStake,
                            cumulativeRisk = parsedStake,
                            ifWinText = "Win Payout $${String.format(Locale.US, "%.2f", parsedStake * 1.10)} (Net +$${String.format(Locale.US, "%.2f", parsedStake * 0.10)}) • Stays Base Differs",
                            ifLossText = "Loss: Next stake $${String.format(Locale.US, "%.2f", parsedStake * 2.0)} • Switches to $recoveryDirection $recoveryDigit Recovery",
                            isBase = true
                        )

                        // Step 2: Recovery 1
                        val s2Stake = parsedStake * 2.0
                        val s2CumRisk = parsedStake + s2Stake
                        val s2Return = s2Stake * (1.0 + overUnderPayoutMultiplier)
                        val s2Net = s2Return - s2CumRisk
                        RecoveryRiskRow(
                            stepNumber = 2,
                            contractType = "Recovery 1 ($recoveryDirection $recoveryDigit)",
                            stake = s2Stake,
                            cumulativeRisk = s2CumRisk,
                            ifWinText = "Win Return $${String.format(Locale.US, "%.2f", s2Return)} (Net +$${String.format(Locale.US, "%.2f", s2Net)}) • Full Recovery! Rolls back to Differs",
                            ifLossText = "Loss: Next stake $${String.format(Locale.US, "%.2f", s2Stake * 2.0)} • Advances to Step 3",
                            isBase = false
                        )

                        // Step 3: Recovery 2
                        val s3Stake = parsedStake * 4.0
                        val s3CumRisk = s2CumRisk + s3Stake
                        val s3Return = s3Stake * (1.0 + overUnderPayoutMultiplier)
                        val s3Net = s3Return - s3CumRisk
                        RecoveryRiskRow(
                            stepNumber = 3,
                            contractType = "Recovery 2 ($recoveryDirection $recoveryDigit)",
                            stake = s3Stake,
                            cumulativeRisk = s3CumRisk,
                            ifWinText = "Win Return $${String.format(Locale.US, "%.2f", s3Return)} (Net +$${String.format(Locale.US, "%.2f", s3Net)}) • Full Recovery! Rolls back to Differs",
                            ifLossText = "Loss: Next stake $${String.format(Locale.US, "%.2f", s3Stake * 2.0)} • Advances to Step 4",
                            isBase = false
                        )

                        // Step 4: Recovery 3
                        val s4Stake = parsedStake * 8.0
                        val s4CumRisk = s3CumRisk + s4Stake
                        val s4Return = s4Stake * (1.0 + overUnderPayoutMultiplier)
                        val s4Net = s4Return - s4CumRisk
                        RecoveryRiskRow(
                            stepNumber = 4,
                            contractType = "Recovery 3 ($recoveryDirection $recoveryDigit)",
                            stake = s4Stake,
                            cumulativeRisk = s4CumRisk,
                            ifWinText = "Win Return $${String.format(Locale.US, "%.2f", s4Return)} (Net +$${String.format(Locale.US, "%.2f", s4Net)}) • Full Recovery! Rolls back to Differs",
                            ifLossText = "Loss: Next stake $${String.format(Locale.US, "%.2f", s4Stake * 2.0)} • Advances to Step 5",
                            isBase = false
                        )

                        // Step 5: Recovery 4
                        val s5Stake = parsedStake * 16.0
                        val s5CumRisk = s4CumRisk + s5Stake
                        val s5Return = s5Stake * (1.0 + overUnderPayoutMultiplier)
                        val s5Net = s5Return - s5CumRisk
                        RecoveryRiskRow(
                            stepNumber = 5,
                            contractType = "Recovery 4 ($recoveryDirection $recoveryDigit)",
                            stake = s5Stake,
                            cumulativeRisk = s5CumRisk,
                            ifWinText = "Win Return $${String.format(Locale.US, "%.2f", s5Return)} (Net +$${String.format(Locale.US, "%.2f", s5Net)}) • Full Recovery! Rolls back to Differs",
                            ifLossText = "Loss: Max recovery cap or Stop Loss protection ($${stopLossText})",
                            isBase = false
                        )
                    }
                }
            }
        }

        // Bottom Save Button Bar
        Surface(
            color = Color(0xFF12141C),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(modifier = Modifier.padding(16.dp)) {
                Button(
                    onClick = {
                        val parsedStakeVal = stakeText.toDoubleOrNull() ?: currentSettings.stakeAmount
                        val parsedTP = takeProfitText.toDoubleOrNull() ?: currentSettings.takeProfit
                        val parsedSL = stopLossText.toDoubleOrNull() ?: currentSettings.stopLoss
                        val parsedDuration = durationValueText.toIntOrNull() ?: currentSettings.durationValue

                        onSaveSettings(
                            TradeSettings(
                                targetDigit = targetDigit,
                                recoveryDigit = recoveryDigit,
                                recoveryDirection = recoveryDirection,
                                comparisonOperator = operator,
                                thresholdPercent = parsedThreshold,
                                analysisMetric = analysisMetric,
                                analysisWindowSize = parsedWindow.coerceIn(
                                    MIN_ANALYSIS_WINDOW,
                                    MAX_ANALYSIS_WINDOW
                                ),
                                stakeAmount = parsedStakeVal,
                                takeProfit = parsedTP,
                                stopLoss = parsedSL,
                                durationUnit = durationUnit,
                                durationValue = parsedDuration,
                                martingaleEnabled = martingale
                            )
                        )
                        onBackClick()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VividYellow,
                        contentColor = Color(0xFF101216)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("save_trade_settings_button")
                ) {
                    Text(
                        text = "Save Trade Settings",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
