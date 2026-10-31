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
import com.tickedge.data.model.EvenOddMode
import com.tickedge.data.model.TradeDurationUnit
import com.tickedge.data.model.TradeSettings
import com.tickedge.domain.RecoveryCalculator
import com.tickedge.domain.StrategyCatalog
import com.tickedge.domain.StrategyDefinition
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
 * Trade configuration for ONE strategy, ordered to follow the decision a trader actually makes:
 *
 *  1. **Ticks to Analyze + entry condition** — the sample window and the buy signal.
 *  2. **Strategy-specific entry** — Differs: which digit the bet is made against, and the
 *     Over/Under recovery ladder. Even/Odd: which parity side to trade (auto / even / odd).
 *  3. **Stake & Tick Duration** — this strategy's own amount and duration.
 *  4. **Risk Management** — this strategy's own take profit and stop loss.
 *
 * Each strategy owns a complete, isolated [TradeSettings] blob — its own stake, duration and
 * stops included — so editing one strategy can never re-tune the other. Saves are persisted per
 * user per strategy (`strategy_settings` table), so signing out and back in restores the same
 * configuration rather than resetting to defaults.
 */
@Composable
fun TradeSettingsScreen(
    strategy: StrategyDefinition,
    currentSettings: TradeSettings,
    onBackClick: () -> Unit,
    onSaveSettings: (TradeSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    val isEvenOdd = strategy.id == StrategyCatalog.EVEN_ODD.id
    var windowText by remember { mutableStateOf("${currentSettings.analysisWindowSize}") }
    var targetDigit by remember { mutableStateOf(currentSettings.targetDigit) }
    var digitMode by remember { mutableStateOf(currentSettings.digitSelectionMode) }
    var operator by remember { mutableStateOf(currentSettings.comparisonOperator) }
    var thresholdText by remember {
        mutableStateOf(String.format(Locale.US, "%.1f", currentSettings.thresholdPercent))
    }
    var recoveryDirection by remember { mutableStateOf(currentSettings.recoveryDirection) }
    var recoveryDigit by remember { mutableStateOf(currentSettings.recoveryDigit) }
    var recoveryOperator by remember { mutableStateOf(currentSettings.recoveryComparisonOperator) }
    var recoveryThresholdText by remember {
        mutableStateOf(String.format(Locale.US, "%.1f", currentSettings.recoveryThresholdPercent))
    }
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

    // Even/Odd strategy fields — only steer the Even/Odd engine. The card is shown for every
    // user because the settings screen is shared; leaving it untouched has no effect on the
    // Differs strategy.
    var evenOddMode by remember { mutableStateOf(currentSettings.evenOddMode) }
    var evenOddOperator by remember { mutableStateOf(currentSettings.evenOddOperator) }
    var evenOddThresholdText by remember {
        mutableStateOf(String.format(Locale.US, "%.1f", currentSettings.evenOddThreshold))
    }

    val parsedWindow = windowText.toIntOrNull()?.coerceIn(MIN_ANALYSIS_WINDOW, MAX_ANALYSIS_WINDOW)
        ?: currentSettings.analysisWindowSize
    val parsedThreshold = thresholdText.toDoubleOrNull()?.coerceIn(0.0, 100.0)
        ?: currentSettings.thresholdPercent
    val parsedRecoveryThreshold = recoveryThresholdText.toDoubleOrNull()?.coerceIn(0.0, 100.0)
        ?: currentSettings.recoveryThresholdPercent
    val parsedStake = stakeText.toDoubleOrNull()?.coerceAtLeast(0.01) ?: currentSettings.stakeAmount
    val parsedDuration = durationValueText.toIntOrNull()?.coerceIn(1, 100) ?: currentSettings.durationValue
    val parsedTakeProfit = takeProfitText.toDoubleOrNull()?.coerceAtLeast(0.0) ?: currentSettings.takeProfit
    val parsedStopLoss = stopLossText.toDoubleOrNull()?.coerceAtLeast(0.0) ?: currentSettings.stopLoss
    val parsedEvenOddThreshold = evenOddThresholdText.toDoubleOrNull()?.coerceIn(0.0, 100.0)
        ?: currentSettings.evenOddThreshold
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
                text = if (isEvenOdd) "Even / Odd · Trade Settings" else "Differs · Trade Settings",
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
            if (isEvenOdd) {
                item {
                    // 1 — How many ticks the bot looks at, and when it enters an Even/Odd trade.
                    SettingsCard(
                        "1 · How much data the bot looks at",
                        "The bot counts the even last digits (0,2,4,6,8) against the odd ones " +
                            "(1,3,5,7,9) in each market. First, choose how many recent ticks to " +
                            "count. Then choose the buy signal: the bot buys the side whose " +
                            "percentage is above (or below) the level you set here. Even/Odd " +
                            "sits near 50% each side, so 55-60% selects a genuinely hot side."
                    ) {
                        AnalysisConditionFields(
                            operator = evenOddOperator,
                            onOperatorChange = { evenOddOperator = it },
                            thresholdText = evenOddThresholdText,
                            onThresholdTextChange = { evenOddThresholdText = it },
                            thresholdPercent = parsedEvenOddThreshold,
                            windowText = windowText,
                            onWindowTextChange = { windowText = it },
                            minWindow = MIN_ANALYSIS_WINDOW,
                            maxWindow = MAX_ANALYSIS_WINDOW,
                            minSampleSize = MIN_ANALYSIS_SAMPLE,
                            baseline = PARITY_BASELINE
                        )
                    }
                }

                // 2 — Which parity side the trade is made on.
                item {
                    SettingsCard(
                        "2 · Which side to bet on",
                        "Even, odd, or let the bot decide each trade: whichever side clears the " +
                            "percentage in card 1. After a loss the recovery bet is also " +
                            "Even/Odd, on whichever side the numbers point at."
                    ) {
                        Text(
                            text = "Which side to bet:",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            EvenOddMode.values().forEach { mode ->
                                Pill(
                                    label = mode.label,
                                    selected = evenOddMode == mode,
                                    modifier = Modifier.weight(1f),
                                    testTag = "even_odd_mode_${mode.symbol}"
                                ) { evenOddMode = mode }
                            }
                        }

                        Text(
                            text = when (evenOddMode) {
                                EvenOddMode.AUTO ->
                                    "The bot picks the side each trade: in \"above\" mode that " +
                                        "is the side showing up more, in \"below\" mode the " +
                                        "side showing up less."
                                EvenOddMode.EVEN ->
                                    "Always the even side. The bot waits for evens to clear " +
                                        "the percentage in card 1, no matter what the odds say."
                                EvenOddMode.ODD ->
                                    "Always the odd side. The bot waits for odds to clear the " +
                                        "percentage in card 1, no matter what the evens say."
                            },
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                item {
                    // 1 — How many ticks the bot looks at, and when it enters a Differs trade.
                    SettingsCard(
                        "1 · How much data the bot looks at",
                        "The bot counts the last few digits in each market. First, choose how " +
                            "many recent ticks to count. Then choose the 'buy signal': when one " +
                            "digit has been appearing less often than the others, that is " +
                            "measured as a percentage. The bot buys when the number is above " +
                            "(or below) the percentage you set here. The Differs bet almost " +
                            "always wins, so its percentage runs high — around 90. A higher " +
                            "number means fewer, safer trades. (The recovery bet has its own, " +
                            "separate percentage in card 3.)"
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

                // 2 — Which digit the bet is made against.
                item {
                    SettingsCard(
                        "2 · Which digit the bet is made against",
                        "The Differs bet is 'this digit will NOT be the next one to appear'. " +
                            "Pick which digit it is."
                    ) {
                        DigitModeToggle(digitMode) { digitMode = it }

                        Text(
                            text = if (digitMode == DigitSelectionMode.FIXED) {
                                "Always the same digit, the one you pick below."
                            } else {
                                "The bot picks a different digit for each trade. It still has " +
                                    "to pass the buy signal in card 1 — if it does not, no " +
                                    "trade is placed."
                            },
                            fontSize = 11.sp,
                            color = TextSecondary
                        )

                        if (digitMode == DigitSelectionMode.FIXED) {
                            DigitGrid(targetDigit) { targetDigit = it }
                        } else {
                            InfoBox(
                                "The bot chooses a different digit on each trade, and looks " +
                                    "at a different market. Manual digit selection is off " +
                                    "while this is on."
                            )
                        }
                    }
                }
            }

            // 4 — What happens when a trade loses. Differs only: the Over/Under ladder has no
            // Even/Odd equivalent, so the parity strategy's screen never shows it.
            if (!isEvenOdd) {
            item {
                SettingsCard(
                    "4 · What happens when a trade loses",
                    "When the bot loses, it can place a bigger Over/Under bet sized to win back " +
                        "the loss. Turn it off and the bot simply stays at the same small stake."
                ) {
                    ToggleRow(
                        label = "Make the next bet bigger after a loss",
                        checked = martingale,
                        onCheckedChange = { martingale = it },
                        testTag = "martingale_toggle"
                    )

                    if (martingale) {
                        Text(
                            text = "Bigger bet on:",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
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

                        // The recovery bet is judged on a different scale from the Differs one:
                        // "over/under a number" wins about 50% of the time, not ~90%. A sensible
                        // Differs threshold can never be satisfied on a 50% scale, so the two are
                        // measured separately rather than sharing one number.
                        Text(
                            text = "Place the recovery bet only when the odds are on our side " +
                                "— at least ${if (isOver) "Over" else "Under"} $recoveryDigit is " +
                                "likely enough. Set the minimum odds you will accept:",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                ComparisonOperator.GREATER_THAN to "Above",
                                ComparisonOperator.LESS_THAN to "Below"
                            ).forEach { (option, label) ->
                                Pill(
                                    label = label,
                                    selected = recoveryOperator == option,
                                    modifier = Modifier.weight(1f),
                                    testTag = "recovery_operator_${option.symbol}"
                                ) { recoveryOperator = option }
                            }
                        }

                        NumberField(
                            value = recoveryThresholdText,
                            onValueChange = { recoveryThresholdText = it },
                            label = "Minimum odds",
                            suffix = "%",
                            decimal = true,
                            testTag = "recovery_threshold_input"
                        )

                        RecoveryPlanPreview(
                            recoveryPreview = recoveryPreview,
                            barrier = recoveryDigit,
                            isOver = isOver
                        )
                    }
                }
            }
            }

            // This strategy's own amount and duration. Card 3 on the Even/Odd screen (no recovery
            // card), card 5 on the Differs screen (after the recovery card).
            item {
                SettingsCard(
                    if (isEvenOdd) "3 · How much to bet, and for how long"
                    else "5 · How much to bet, and for how long",
                    "The amount on each starting trade, and how long each bet runs before it " +
                        "is decided."
                ) {
                    NumberField(
                        value = stakeText,
                        onValueChange = { stakeText = it },
                        label = "Amount per trade",
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
                        label = "How long each bet runs",
                        suffix = durationUnit.shortLabel,
                        decimal = false,
                        testTag = "duration_input"
                    )
                }
            }

            // This strategy's own stop levels.
            item {
                SettingsCard(
                    if (isEvenOdd) "4 · When the bot stops by itself"
                    else "6 · When the bot stops by itself",
                    "The run ends on its own when it makes this much, or when a losing streak " +
                        "costs this much."
                ) {
                    NumberField(
                        value = takeProfitText,
                        onValueChange = { takeProfitText = it },
                        label = "Stop when the run has won",
                        suffix = "$",
                        decimal = true,
                        testTag = "take_profit_input"
                    )
                    NumberField(
                        value = stopLossText,
                        onValueChange = { stopLossText = it },
                        label = "Stop when a losing streak costs",
                        suffix = "$",
                        decimal = true,
                        testTag = "stop_loss_input"
                    )
                    Text(
                        text = "A win that wins back the loss resets the 'losing streak' number — " +
                            "it is the hole you are in right now, not everything lost today.",
                        fontSize = 11.sp,
                        color = TextSecondary
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
                                recoveryComparisonOperator = recoveryOperator,
                                recoveryThresholdPercent = parsedRecoveryThreshold,
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
                                martingaleEnabled = martingale,
                                evenOddMode = evenOddMode,
                                evenOddOperator = evenOddOperator,
                                evenOddThreshold = parsedEvenOddThreshold
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
            text = "This setup does not work out. See card 4.",
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
            text = "If you lose two bets in a row, here is how much the next ones need to be " +
                "just to win it back",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Text(
            text = "An ${if (isOver) "Over" else "Under"} $barrier bet wins when the next tick " +
                "is one of $winningDigits out of 10 possible digits — about " +
                "${String.format(Locale.US, "%.0f", winProbability * 100)}% of the time, paying " +
                "${String.format(Locale.US, "%.3f", recoveryPreview.payoutRatio)} for every $1 " +
                "bet.",
            fontSize = 11.sp,
            color = TextSecondary
        )

        Box(modifier = Modifier.padding(top = 6.dp))

        recoveryPreview.steps.forEach { step ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Bet ${step.step}", fontSize = 11.sp, color = TextSecondary)
                Text(
                    text = String.format(Locale.US, "you would bet %.2f", step.stake),
                    fontSize = 11.sp,
                    color = TextPrimary
                )
                Text(
                    text = String.format(Locale.US, "total risk %.2f", step.cumulativeRisk),
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
                title = "This ladder would need to get too big",
                body = recoveryPreview.infeasibleReason
                    ?: "The next bets needed to win back the loss grow beyond what the app will " +
                        "allow.",
                tint = StatRed,
                background = Color(0xFF241016),
                borderColor = Color(0xFF6B2F3A)
            )
            Box(modifier = Modifier.padding(top = 6.dp))
        }

        Text(
            text = "Estimate only, assuming two bets have already been lost. These bigger " +
                "recovery bets win less often and lose more when they are wrong, so they can " +
                "cost more than they return — which is exactly why the stop loss in card 6 " +
                "matters: it is the point where the bot gives up on winning it back.",
            fontSize = 10.sp,
            color = TextSecondary.copy(alpha = 0.75f)
        )
    }
}
