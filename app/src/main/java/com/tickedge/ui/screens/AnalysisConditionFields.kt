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
import com.tickedge.ui.theme.BorderDark
import com.tickedge.ui.theme.TextPrimary
import com.tickedge.ui.theme.TextSecondary
import com.tickedge.ui.theme.VividYellow
import java.util.Locale

/**
 * The three controls that decide *what* the entry threshold measures and *over how much data*:
 * the metric toggle, the sample window, and the warning that fires when the two are mismatched.
 *
 * Extracted from [TradeSettingsScreen] to keep that file under the maintainability ceiling.
 *
 * The warning matters more than it looks. P(last digit != target) sits near 90% on live ticks by
 * construction, so a Differs-% threshold below the 90s is not a filter — it passes on virtually
 * every tick and the bot enters continuously. The user's number is never rewritten for them; the
 * screen just tells them which regime they are in.
 */
@Composable
fun AnalysisConditionFields(
    targetDigit: Int,
    metric: AnalysisMetric,
    onMetricChange: (AnalysisMetric) -> Unit,
    windowText: String,
    onWindowTextChange: (String) -> Unit,
    thresholdPercent: Double,
    minWindow: Int,
    maxWindow: Int,
    differsFloorThreshold: Double,
    modifier: Modifier = Modifier
) {
    val thresholdIsTooLow =
        metric == AnalysisMetric.DIFFERS_PERCENT && thresholdPercent < differsFloorThreshold

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
                    text = "Sample window every percentage here is computed over ($minWindow–$maxWindow).",
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

        if (thresholdIsTooLow) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF241016), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF6B2F3A), RoundedCornerShape(10.dp))
                    .padding(12.dp)
                    .testTag("differs_threshold_warning")
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Threshold warning",
                        tint = Color(0xFFF0879A),
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "This threshold will fire almost every tick",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF0879A)
                        )
                        Text(
                            text = "Differs % normally sits near 90% on live ticks, so " +
                                "${String.format(Locale.US, "%.1f", thresholdPercent)}% is met " +
                                "constantly and the bot will enter continuously. Use a threshold " +
                                "above ${String.format(Locale.US, "%.0f", differsFloorThreshold)}% " +
                                "to trade only when digit $targetDigit is running cold.",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}
