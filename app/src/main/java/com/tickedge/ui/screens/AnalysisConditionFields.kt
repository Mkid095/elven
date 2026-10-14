package com.tickedge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tickedge.data.model.ComparisonOperator
import com.tickedge.ui.theme.BorderDark
import com.tickedge.ui.theme.TextPrimary
import com.tickedge.ui.theme.TextSecondary
import com.tickedge.ui.theme.VividYellow
import java.util.Locale

/**
 * Ordinary level of Differs % on live Deriv synthetic ticks.
 *
 * Ten last digits on a roughly uniform stream give P(last digit != d) ~90%. A threshold at or below
 * that describes the market's normal behaviour rather than selecting a moment, which is what the
 * warning below is for.
 */
private const val DIFFERS_BASELINE = 90.0

/**
 * The two analysis controls: how many ticks are analysed, and the above/below entry condition.
 *
 * The metric is deliberately **not** a control. An earlier version offered "Differs %" vs
 * "Equals %" because the engine had been reading the wrong one; now that it reads Differs — the
 * only thing the app trades — offering a choice between the two is a way to configure the app into
 * behaving incorrectly. `AnalysisMetric` still exists in the model purely so already-migrated
 * databases keep validating; the engine ignores it and always measures Differs.
 *
 * Extracted from [TradeSettingsScreen] to keep that file under the maintainability ceiling.
 */
@Composable
fun AnalysisConditionFields(
    operator: ComparisonOperator,
    onOperatorChange: (ComparisonOperator) -> Unit,
    thresholdText: String,
    onThresholdTextChange: (String) -> Unit,
    thresholdPercent: Double,
    windowText: String,
    onWindowTextChange: (String) -> Unit,
    minWindow: Int,
    maxWindow: Int,
    minSampleSize: Int,
    modifier: Modifier = Modifier
) {
    // A condition at or below the baseline is satisfied by ordinary market behaviour, so it
    // qualifies on nearly every tick rather than selecting anything.
    val firesConstantly = when (operator) {
        ComparisonOperator.GREATER_THAN -> thresholdPercent <= DIFFERS_BASELINE
        ComparisonOperator.GREATER_OR_EQUAL -> thresholdPercent < DIFFERS_BASELINE
        ComparisonOperator.LESS_THAN -> thresholdPercent >= DIFFERS_BASELINE
        ComparisonOperator.LESS_OR_EQUAL -> thresholdPercent > DIFFERS_BASELINE
    }

    val windowTooSmall = (windowText.toIntOrNull() ?: minWindow) < minSampleSize

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = windowText,
            // Digits only, capped at 4 chars: 1000 is the retained-history ceiling, so anything
            // longer is not a value the pipeline could honour.
            onValueChange = { input -> onWindowTextChange(input.filter { it.isDigit() }.take(4)) },
            label = { Text("Ticks to Analyze") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            trailingIcon = {
                Text(text = "ticks", color = TextSecondary, modifier = Modifier.padding(end = 12.dp))
            },
            supportingText = {
                Text(
                    text = "Sample window used by both Differs and Over/Under analysis ($minWindow–$maxWindow).",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = VividYellow,
                unfocusedBorderColor = BorderDark,
                focusedLabelColor = VividYellow,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedContainerColor = Color(0xFF13151D),
                unfocusedContainerColor = Color(0xFF13151D),
                focusedSupportingTextColor = TextSecondary,
                unfocusedSupportingTextColor = TextSecondary
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().testTag("analysis_window_input")
        )

        if (windowTooSmall) {
            WarningCard(
                testTag = "window_too_small_warning",
                title = "This window is too small to ever trigger an entry",
                body = "An entry needs at least $minSampleSize valid ticks before the threshold is " +
                    "evaluated at all. At ${windowText.ifBlank { "0" }} ticks the bot will analyse but " +
                    "never trade.",
                tint = VividYellow,
                background = Color(0xFF241A10),
                borderColor = Color(0xFF6B4A2F)
            )
        }

        Text(
            text = "Enter when Differs % is:",
            fontSize = 13.sp,
            color = TextSecondary,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            listOf(
                ComparisonOperator.GREATER_THAN to "Above",
                ComparisonOperator.LESS_THAN to "Below"
            ).forEach { (option, label) ->
                val isSelected = operator == option
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) VividYellow else Color(0xFF232734))
                        .clickable { onOperatorChange(option) }
                        .padding(vertical = 10.dp)
                        .testTag("operator_${option.symbol}"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) Color(0xFF101216) else TextPrimary
                    )
                }
            }
        }

        OutlinedTextField(
            value = thresholdText,
            // Digits and at most one decimal point — a percentage.
            onValueChange = { input ->
                val cleaned = input.filter { it.isDigit() || it == '.' }
                    .let { text ->
                        val firstDot = text.indexOf('.')
                        if (firstDot == -1) text
                        else text.substring(0, firstDot + 1) + text.substring(firstDot + 1).replace(".", "")
                    }
                onThresholdTextChange(cleaned.take(6))
            },
            label = { Text("Threshold Percentage") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            trailingIcon = {
                Text(
                    text = "%",
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
            modifier = Modifier.fillMaxWidth().testTag("threshold_input")
        )

        if (firesConstantly) {
            WarningCard(
                testTag = "threshold_fires_constantly_warning",
                title = if (operator == ComparisonOperator.GREATER_THAN ||
                    operator == ComparisonOperator.GREATER_OR_EQUAL
                ) {
                    "This threshold will qualify almost every tick"
                } else {
                    "This threshold is rare — the bot may never trade"
                },
                body = "Differs % normally sits near " +
                    "${String.format(Locale.US, "%.0f", DIFFERS_BASELINE)}% on live ticks. " +
                    (if (operator == ComparisonOperator.GREATER_THAN ||
                        operator == ComparisonOperator.GREATER_OR_EQUAL
                    ) {
                        "A threshold at or below that is ordinary market behaviour, not a filter, " +
                            "so the bot would enter continuously."
                    } else {
                        "Entering only below this figure needs the target digit to be running well " +
                            "above its usual share — and a contract that rarely wins."
                    }),
                tint = Color(0xFFF0879A),
                background = Color(0xFF241016),
                borderColor = Color(0xFF6B2F3A)
            )
        }

        Text(
            text = "Percentages describe what has already happened in the window. They are not a " +
                "prediction of the next tick, and a qualifying reading does not guarantee a win.",
            fontSize = 10.sp,
            color = TextSecondary.copy(alpha = 0.75f)
        )
    }
}

@Composable
fun WarningCard(
    testTag: String,
    title: String,
    body: String,
    tint: Color,
    background: Color,
    borderColor: Color
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(12.dp)
            .testTag(testTag)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = title,
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
            Column {
                Text(text = title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = tint)
                Text(text = body, fontSize = 11.sp, color = TextSecondary)
            }
        }
    }
}
