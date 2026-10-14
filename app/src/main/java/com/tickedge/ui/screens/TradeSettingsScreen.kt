package com.tickedge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tickedge.data.model.AnalysisMetric
import com.tickedge.data.model.ComparisonOperator
import com.tickedge.data.model.DigitSelectionMode
import com.tickedge.data.model.TradeDurationUnit
import com.tickedge.data.model.TradeSettings
import com.tickedge.domain.RecoveryCalculator
import com.tickedge.ui.theme.BorderDark
import com.tickedge.ui.theme.StatRed
import com.tickedge.ui.theme.SurfaceDarkCard
import com.tickedge.ui.theme.TextPrimary
import com.tickedge.ui.theme.TextSecondary
import com.tickedge.ui.theme.VividYellow
import java.util.Locale

private const val MIN_ANALYSIS_WINDOW = 1
private const val MAX_ANALYSIS_WINDOW = 1000
private const val MIN_ANALYSIS_SAMPLE = 10

/**
 * Trade configuration, ordered to follow the decision a trader actually makes:
 *
 *  1. **Ticks to Analyze** — the sample window. First because it governs every percentage below
 *     it, and it applies to both strategies.
 *  2. **Target Number for Differs** — automated or manual digit selection.
 *  3. **Differs Entry Condition** — above or below a percentage.
 *  4. **Automated Recovery** — which Over/Under digit to roll back on, with the recovery ladder
 *     shown up front so its cost is visible before it is incurred.
 *  5. **Stake & Tick Duration** — defaults 0.35 and 1 tick.
 *  6. **Risk Management** — take profit and stop loss.
 *
 * The whole thing saves as one [TradeSettings] and is persisted per user, so signing out and back
 * in restores the same configuration rather than resetting to defaults.
 */
@Composable
fun TradeSettingsScreen(
    currentSettings: TradeSettings,
    onBackClick: () -> Unit,
    onSaveSettings: (TradeSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    var windowText by remember { mutableStateOf("${currentSettings.analysisWindowSize}") }
    var targetDigit by remember { mutableStateOf(currentSettings.targetDigit) }
    var digitMode by remember { mutableStateOf(currentSettings.digitSelectionMode) }
    var operator by remember { mutableStateOf(currentSettings.comparisonOperator) }
    var thresholdText by remember {
        mutableStateOf(String.format(Locale.US, "%.1f", currentSettings.thresholdPercent))
    }
    var recoveryDirection by remember { mutableStateOf(currentSettings.recoveryDirection) }
    var recoveryDigit by remember { mutableStateOf(currentSettings.recoveryDigit) }
    var stakeText by remember {
        mutableStateOf(String.format(Locale.US, "%.2f", currentSettings.stakeAmount))
    }
    var durationUnit by remember { mutableStateOf(currentSettings.durationUnit) }
    var durationValueText by remember { mutableStateOf("${currentSettings.durationValue}") }
    var takeProfitText by remember {
        mutableStateOf(String.format(Locale.US, "%.2f", currentSettings.takeProfit))
    }
    var stopLossText by remember {
        mutableStateOf(String.format(Locale.US, "%.2f", currentSettings.stopLoss))
    }
    var martingale by remember { mutableStateOf(currentSettings.martingaleEnabled) }

    val parsedWindow = windowText.toIntOrNull()?.coerceIn(MIN_ANALYSIS_WINDOW, MAX_ANALYSIS_WINDOW)
        ?: currentSettings.analysisWindowSize
    val parsedThreshold = thresholdText.toDoubleOrNull()?.coerceIn(0.0, 100.0)
        ?: currentSettings.thresholdPercent
    val parsedStake = stakeText.toDoubleOrNull()?.coerceAtLeast(0.01) ?: currentSettings.stakeAmount
    val parsedDuration = durationValueText.toIntOrNull()?.coerceIn(1, 100) ?: currentSettings.durationValue
    val parsedTakeProfit = takeProfitText.toDoubleOrNull()?.coerceAtLeast(0.0) ?: currentSettings.takeProfit
    val parsedStopLoss = stopLossText.toDoubleOrNull()?.coerceAtLeast(0.0) ?: currentSettings.stopLoss
    val isOver = recoveryDirection.equals("OVER", ignoreCase = true)

    // Live preview of what recovery would actually cost at the chosen Over/Under barrier, assuming
    // two base stakes have already been lost.
    val recoveryPreview = remember(parsedStake, recoveryDigit, isOver) {
        runCatching {
            RecoveryCalculator.plan(
                baseStake = parsedStake,
                lossesToRecover = parsedStake * 2,
                winProbability = RecoveryCalculator.overUnderWinProbability(recoveryDigit, isOver),
                maxSteps = 3,
                maxTotalRisk = 100.0
            )
        }.getOrNull()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D0E12))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick, modifier = Modifier.testTag("settings_back_button")) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = VividYellow
                )
            }
            Text(
                text = "Trade Settings",
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1 — Ticks to Analyze, first because everything below is computed over it. The entry
            //     condition lives here too so the window and the threshold sit together; the card
            //     heading names both so the ordering still reads correctly.
            item {
                SettingsCard(
                    "1 · Ticks to Analyze & 3 · Entry Condition",
                    "Every percentage below is measured over this window, for both Differs and Over/Under."
                ) {
                    AnalysisConditionFields(
                        operator = operator,
                        onOperatorChange = { operator = it },
                        thresholdText = thresholdText,
                        onThresholdTextChange = { thresholdText = it },
                        thresholdPercent = parsedThreshold,
                        windowText = windowText,
                        onWindowTextChange = { windowText = it },
                        minWindow = MIN_ANALYSIS_WINDOW,
                        maxWindow = MAX_ANALYSIS_WINDOW,
                        minSampleSize = MIN_ANALYSIS_SAMPLE
                    )
                }
            }

            // 2 — Target digit, automated or manual.
            item {
                SettingsCard("2 · Target Number for Differs", "The digit the exit tick must NOT match.") {
                    DigitModeToggle(digitMode) { digitMode = it }

                    Text(
                        text = if (digitMode == DigitSelectionMode.FIXED) {
                            "Always trades the digit you select below."
                        } else {
                            "A fresh digit is drawn per opportunity and still has to pass the entry " +
                                "condition above — a draw that fails simply places no trade."
                        },
                        fontSize = 11.sp,
                        color = TextSecondary
                    )

                    if (digitMode == DigitSelectionMode.FIXED) {
                        DigitGrid(targetDigit) { targetDigit = it }
                    } else {
                        InfoBox(
                            "Automated digits are on. The bot will trade a random market on every " +
                                "trade and a different digit each time. Manual digit selection is " +
                                "disabled while this is on."
                        )
                    }
                }
            }

            // 4 — Automated recovery.
            item {
                SettingsCard(
                    "4 · Automated Recovery",
                    "When a Differs trade loses, recovery bets Over/Under on this digit to recover."
                ) {
                    ToggleRow(
                        label = "Enable Martingale recovery",
                        checked = martingale,
                        onCheckedChange = { martingale = it },
                        testTag = "martingale_toggle"
                    )

                    if (martingale) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("OVER" to "Over", "UNDER" to "Under").forEach { (value, label) ->
                                Pill(
                                    label = label,
                                    selected = recoveryDirection.equals(value, ignoreCase = true),
                                    modifier = Modifier.weight(1f),
                                    testTag = "recovery_direction_$value"
                                ) { recoveryDirection = value }
                            }
                        }

                        DigitGrid(recoveryDigit) { recoveryDigit = it }

                        RecoveryPlanPreview(
                            recoveryPreview = recoveryPreview,
                            barrier = recoveryDigit,
                            isOver = isOver
                        )
                    }
                }
            }

            // 5 — Stake and contract duration.
            item {
                SettingsCard("5 · Stake & Tick Duration", "Amount per trade and how long each contract runs.") {
                    NumberField(
                        value = stakeText,
                        onValueChange = { stakeText = it },
                        label = "Stake Amount",
                        suffix = "$",
                        decimal = true,
                        testTag = "stake_input"
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TradeDurationUnit.values().forEach { unit ->
                            Pill(
                                label = unit.label,
                                selected = durationUnit == unit,
                                modifier = Modifier.weight(1f),
                                testTag = "duration_unit_${unit.label}"
                            ) { durationUnit = unit }
                        }
                    }
                    NumberField(
                        value = durationValueText,
                        onValueChange = { durationValueText = it },
                        label = "Contract Duration",
                        suffix = durationUnit.shortLabel,
                        decimal = false,
                        testTag = "duration_input"
                    )
                }
            }

            // 6 — Risk management.
            item {
                SettingsCard("6 · Risk Management", "Session limits. Trading stops automatically when reached.") {
                    NumberField(
                        value = takeProfitText,
                        onValueChange = { takeProfitText = it },
                        label = "Take Profit",
                        suffix = "$",
                        decimal = true,
                        testTag = "take_profit_input"
                    )
                    NumberField(
                        value = stopLossText,
                        onValueChange = { stopLossText = it },
                        label = "Stop Loss",
                        suffix = "$",
                        decimal = true,
                        testTag = "stop_loss_input"
                    )
                }
            }

            item {
                Button(
                    onClick = {
                        onSaveSettings(
                            TradeSettings(
                                targetDigit = targetDigit,
                                recoveryDigit = recoveryDigit,
                                recoveryDirection = recoveryDirection,
                                comparisonOperator = operator,
                                thresholdPercent = parsedThreshold,
                                // Always Differs. The app trades nothing else, so exposing a
                                // choice here would only let it be configured wrongly. The field
                                // stays in the model so migrated databases keep validating.
                                analysisMetric = AnalysisMetric.DIFFERS_PERCENT,
                                digitSelectionMode = digitMode,
                                analysisWindowSize = parsedWindow,
                                stakeAmount = parsedStake,
                                takeProfit = parsedTakeProfit,
                                stopLoss = parsedStopLoss,
                                durationUnit = durationUnit,
                                durationValue = parsedDuration,
                                martingaleEnabled = martingale
                            )
                        )
                        onBackClick()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VividYellow),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("save_settings_button")
                ) {
                    Text("Save Settings", color = Color(0xFF101216), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- shared pieces

@Composable
private fun SettingsCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
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
            Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = VividYellow)
            Text(text = subtitle, fontSize = 11.sp, color = TextSecondary)
            content()
        }
    }
}

@Composable
private fun InfoBox(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF171A24), RoundedCornerShape(10.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Text(text = text, fontSize = 11.sp, color = TextSecondary)
    }
}

@Composable
private fun DigitGrid(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        (0..9).forEach { d ->
            val isSelected = selected == d
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) VividYellow else Color(0xFF232734))
                    .clickable { onSelect(d) }
                    .testTag("digit_$d"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$d",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) Color(0xFF101216) else TextPrimary
                )
            }
        }
    }
}

@Composable
private fun DigitModeToggle(mode: DigitSelectionMode, onChange: (DigitSelectionMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DigitSelectionMode.values().forEach { option ->
            Pill(
                label = option.label,
                selected = mode == option,
                modifier = Modifier.weight(1f),
                testTag = "digit_mode_${option.symbol}"
            ) { onChange(option) }
        }
    }
}

@Composable
private fun Pill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    testTag: String,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) VividYellow else Color(0xFF232734))
            .clickable { onClick() }
            .padding(vertical = 10.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) Color(0xFF101216) else TextPrimary
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 13.sp, color = TextPrimary)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF101216),
                checkedTrackColor = VividYellow,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = Color(0xFF232734)
            ),
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suffix: String,
    decimal: Boolean,
    testTag: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            onValueChange(
                if (decimal) {
                    input.filter { it.isDigit() || it == '.' }.let { text ->
                        val dot = text.indexOf('.')
                        if (dot == -1) text
                        else text.substring(0, dot + 1) + text.substring(dot + 1).replace(".", "")
                    }.take(10)
                } else {
                    input.filter { it.isDigit() }.take(4)
                }
            )
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number
        ),
        trailingIcon = {
            Text(
                text = suffix,
                color = VividYellow,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 12.dp)
            )
        },
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
        modifier = Modifier.fillMaxWidth().testTag(testTag)
    )
}

/**
 * Live preview of the recovery ladder for the chosen Over/Under barrier.
 *
 * Shown before any loss happens, because the cost of recovering is invisible until you are already
 * down. Each row reports the *cumulative exposure*, not just the next stake — the exposure is the
 * number that actually decides whether continuing is survivable.
 */
@Composable
private fun RecoveryPlanPreview(
    recoveryPreview: RecoveryCalculator.Plan?,
    barrier: Int,
    isOver: Boolean
) {
    if (recoveryPreview == null) {
        Text(
            text = "Recovery ladder unavailable for this configuration.",
            fontSize = 11.sp,
            color = StatRed
        )
        return
    }

    val winProbability = RecoveryCalculator.overUnderWinProbability(barrier, isOver)
    val winningDigits = if (isOver) (barrier + 1).coerceAtMost(9) else barrier.coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF171A24), RoundedCornerShape(10.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
            .padding(12.dp)
            .testTag("recovery_plan_preview")
    ) {
        Text(
            text = "Recovery ladder",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Text(
            text = "${if (isOver) "Over" else "Under"} $barrier wins on $winningDigits of 10 digits " +
                "— ${String.format(Locale.US, "%.0f", winProbability * 100)}% chance, paying " +
                "${String.format(Locale.US, "%.3f", recoveryPreview.payoutRatio)} per unit staked.",
            fontSize = 11.sp,
            color = TextSecondary
        )

        Box(modifier = Modifier.padding(top = 6.dp))

        recoveryPreview.steps.forEach { step ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Step ${step.step}", fontSize = 11.sp, color = TextSecondary)
                Text(
                    text = String.format(Locale.US, "stake %.2f", step.stake),
                    fontSize = 11.sp,
                    color = TextPrimary
                )
                Text(
                    text = String.format(Locale.US, "exposure %.2f", step.cumulativeRisk),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (step.cumulativeRisk > 50.0) StatRed else VividYellow
                )
            }
            Box(modifier = Modifier.padding(top = 4.dp))
        }

        if (!recoveryPreview.isFeasible) {
            WarningCard(
                testTag = "recovery_infeasible_warning",
                title = "Recovery is not achievable within the limit",
                body = recoveryPreview.infeasibleReason
                    ?: "The ladder exceeds the configured risk ceiling.",
                tint = StatRed,
                background = Color(0xFF241016),
                borderColor = Color(0xFF6B2F3A)
            )
            Box(modifier = Modifier.padding(top = 6.dp))
        }

        Text(
            text = "Estimate only, assuming two base stakes already lost. Each rung is a separate " +
                "bet with a negative expected value — recovering past losses costs more than it " +
                "returns, and a losing streak ends the run.",
            fontSize = 10.sp,
            color = TextSecondary.copy(alpha = 0.75f)
        )
    }
}
