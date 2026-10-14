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
import com.tickedge.data.model.AnalysisMetric
import com.tickedge.data.model.ComparisonOperator
import com.tickedge.ui.theme.BorderDark
import com.tickedge.ui.theme.TextPrimary
import com.tickedge.ui.theme.TextSecondary
import com.tickedge.ui.theme.VividYellow
import java.util.Locale

/**
 * Ordinary level of each metric on live Deriv synthetic ticks.
 *
 * Ten last digits on a roughly uniform stream give P(equals) ~10% and P(differs) ~90%. These are
 * what a "filter" has to beat; a threshold at or below the baseline describes the market's normal
 * behaviour rather than selecting a moment.
 */
private const val DIFFERS_BASELINE = 90.0
private const val EQUALS_BASELINE = 10.0

/**
 * Controls deciding *what* the entry threshold measures and *over how much data*: the metric
 * toggle, the sample window, and the warnings that fire when the combination is self-defeating.
 *
 * Extracted from [TradeSettingsScreen] to keep that file under the maintainability ceiling.
 *
 * Neither warning blocks saving or rewrites the user's number. The threshold is theirs; the screen
 * only states plainly which regime the combination puts them in.
 */
@Composable
fun AnalysisConditionFields(
    targetDigit: Int,
    metric: AnalysisMetric,
    onMetricChange: (AnalysisMetric) -> Unit,
    operator: ComparisonOperator,
    thresholdPercent: Double,
    windowText: String,
    onWindowTextChange: (String) -> Unit,
    minWindow: Int,
    maxWindow: Int,
    minSampleSize: Int,
    modifier: Modifier = Modifier
) {
    val baseline = if (metric == AnalysisMetric.DIFFERS_PERCENT) DIFFERS_BASELINE else EQUALS_BASELINE
    val metricName = if (metric == AnalysisMetric.DIFFERS_PERCENT) "Differs %" else "Equals %"

    // A condition at or below the baseline is satisfied by ordinary market behaviour, so it
    // qualifies on nearly every tick rather than selecting anything.
    val firesConstantly = when (operator) {
        ComparisonOperator.GREATER_THAN -> thresholdPercent <= baseline
        ComparisonOperator.GREATER_OR_EQUAL -> thresholdPercent < baseline
        ComparisonOperator.LESS_THAN -> thresholdPercent >= baseline
        ComparisonOperator.LESS_OR_EQUAL -> thresholdPercent > baseline
    }

    val windowTooSmall = (windowText.toIntOrNull() ?: minWindow) < minSampleSize

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Measure:",
            fontSize = 13.sp,
            color = TextSecondary,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AnalysisMetric.values().forEach { option ->
                val isSelected = metric == option
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) VividYellow else Color(0xFF232734))
                        .clickable { onMetricChange(option) }
                        .padding(vertical = 10.dp)
                        .testTag("metric_${option.symbol}"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = option.label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) Color(0xFF101216) else TextPrimary
                    )
                }
            }
        }

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
                    text = "Sample window every percentage is computed over ($minWindow–$maxWindow).",
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

        if (firesConstantly) {
            WarningCard(
                testTag = "threshold_fires_constantly_warning",
                title = "This threshold will qualify almost every tick",
                body = "$metricName normally sits near " +
                    "${String.format(Locale.US, "%.0f", baseline)}% on live ticks, so " +
                    "${String.format(Locale.US, "%.1f", thresholdPercent)}% " +
                    "${operator.label.lowercase()} describes ordinary market behaviour rather than " +
                    "a selectable moment. The bot will enter continuously.",
                tint = Color(0xFFF0879A),
                background = Color(0xFF241016),
                borderColor = Color(0xFF6B2F3A)
            )
        }

        Text(
            text = "Percentages are measured over recent ticks only. They describe what has already " +
                "happened in the window — they are not a prediction of the next tick, and a " +
                "qualifying reading is not a guarantee that the contract will win.",
            fontSize = 10.sp,
            color = TextSecondary.copy(alpha = 0.75f)
        )
    }
}

@Composable
private fun WarningCard(
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
