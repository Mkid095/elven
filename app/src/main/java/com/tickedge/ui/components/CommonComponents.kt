package com.tickedge.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tickedge.data.model.ConnectionStatus
import com.tickedge.data.model.DigitFrequency
import com.tickedge.data.model.MarketState
import com.tickedge.data.model.TrendDirection
import com.tickedge.ui.theme.BorderDark
import com.tickedge.ui.theme.StatGreen
import com.tickedge.ui.theme.StatRed
import com.tickedge.ui.theme.SurfaceDarkCard
import com.tickedge.ui.theme.TextPrimary
import com.tickedge.ui.theme.TextSecondary
import com.tickedge.ui.theme.VividYellow
import java.util.Locale

@Composable
fun ConnectionBadge(
    status: ConnectionStatus,
    modifier: Modifier = Modifier
) {
    val (dotColor, label) = when (status) {
        ConnectionStatus.LIVE -> StatGreen to "Live"
        ConnectionStatus.LOADING_HISTORY -> VividYellow to "Syncing"
        ConnectionStatus.CONNECTING -> VividYellow to "Connecting"
        ConnectionStatus.RECONNECTING -> VividYellow to "Reconnecting"
        ConnectionStatus.STALE -> StatRed to "Stale"
        ConnectionStatus.OFFLINE -> StatRed to "Offline"
        ConnectionStatus.ERROR -> StatRed to "Error"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .background(Color(0xFF1E212B), RoundedCornerShape(12.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(dotColor, CircleShape)
        )
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = TextSecondary
        )
    }
}

@Composable
fun MarketMiniCard(
    marketState: MarketState,
    targetDigit: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val targetFreq = marketState.digitFrequencies.firstOrNull { it.digit == targetDigit }
    val pct = targetFreq?.percentage ?: 0.0
    val trend = targetFreq?.trend ?: TrendDirection.EQUAL

    Surface(
        onClick = onClick,
        color = SurfaceDarkCard,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = marketState.displayName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            if (marketState.connectionStatus == ConnectionStatus.LIVE) StatGreen else StatRed,
                            CircleShape
                        )
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Last quote with highlighted last digit
                Row(verticalAlignment = Alignment.Bottom) {
                    val fullQuote = marketState.lastFormattedQuote
                    if (fullQuote.length > 1) {
                        Text(
                            text = fullQuote.dropLast(1),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextSecondary
                        )
                        Text(
                            text = fullQuote.takeLast(1),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = VividYellow
                        )
                    } else {
                        Text(
                            text = fullQuote,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextSecondary
                        )
                    }
                }

                // Probability badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = String.format(Locale.US, "%.0f%%", pct),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = VividYellow
                    )
                    when (trend) {
                        TrendDirection.UP -> Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "Trending Up",
                            tint = StatGreen,
                            modifier = Modifier.size(13.dp)
                        )
                        TrendDirection.DOWN -> Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "Trending Down",
                            tint = StatRed,
                            modifier = Modifier.size(13.dp)
                        )
                        TrendDirection.EQUAL -> Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = "Neutral",
                            tint = TextSecondary,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }

            // Visual frequency bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF282C38))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth((pct / 100f).toFloat().coerceIn(0f, 1f))
                        .background(VividYellow)
                )
            }
        }
    }
}

@Composable
fun DigitFrequencyRow(
    frequency: DigitFrequency,
    isSelected: Boolean,
    maxPercentage: Double,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = if (maxPercentage > 0) (frequency.percentage / maxPercentage).toFloat() else 0f
    val animatedProgress by animateFloatAsState(targetValue = progress.coerceIn(0.02f, 1f), label = "prog")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) Color(0xFF2C2812) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Digit badge
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(
                    if (isSelected) VividYellow else Color(0xFF232734),
                    RoundedCornerShape(8.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = frequency.digit.toString(),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = if (isSelected) Color(0xFF101216) else TextPrimary
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Progress Bar
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF232734))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedProgress)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isSelected) VividYellow else Color(0xFFFFD200).copy(alpha = 0.65f))
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Count
        Text(
            text = "${frequency.count}",
            fontSize = 12.sp,
            color = TextSecondary,
            modifier = Modifier.width(32.dp),
            textAlign = TextAlign.End
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Probability Percentage
        Text(
            text = String.format(Locale.US, "%.1f%%", frequency.percentage),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isSelected) VividYellow else TextPrimary,
            modifier = Modifier.width(50.dp),
            textAlign = TextAlign.End
        )

        Spacer(modifier = Modifier.width(6.dp))

        // Trend Icon
        when (frequency.trend) {
            TrendDirection.UP -> Icon(
                imageVector = Icons.Default.ArrowUpward,
                contentDescription = "Up",
                tint = StatGreen,
                modifier = Modifier.size(14.dp)
            )
            TrendDirection.DOWN -> Icon(
                imageVector = Icons.Default.ArrowDownward,
                contentDescription = "Down",
                tint = StatRed,
                modifier = Modifier.size(14.dp)
            )
            TrendDirection.EQUAL -> Icon(
                imageVector = Icons.Default.Remove,
                contentDescription = "Neutral",
                tint = TextSecondary,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
fun CircularCountdown(
    remainingSeconds: Int,
    totalSeconds: Int,
    outcome: com.tickedge.data.model.TradeOutcome? = null,
    recentTrade: com.tickedge.data.model.SimulatedTrade? = null,
    isTradeActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isWin = outcome == com.tickedge.data.model.TradeOutcome.WON
    val isLoss = outcome == com.tickedge.data.model.TradeOutcome.LOST
    val hasOutcome = outcome != null

    // Determine target color: Green for Won, Red for Lost, Vivid Yellow for Active trade, Muted for Idle
    val targetColor = when {
        isWin -> StatGreen
        isLoss -> StatRed
        isTradeActive -> VividYellow
        else -> Color(0xFF6B7280)
    }

    val animatedColor by androidx.compose.animation.animateColorAsState(
        targetValue = targetColor,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 400),
        label = "clockColor"
    )

    val progress = if (totalSeconds > 0) {
        (remainingSeconds.toFloat() / totalSeconds.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val targetSweep = if (hasOutcome) 1f else progress
    val animatedProgress by animateFloatAsState(
        targetValue = targetSweep,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 350),
        label = "clockProgress"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(130.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 9.dp.toPx()
            // Background track
            drawArc(
                color = Color(0xFF1E222D),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            // Active / outcome arc
            drawArc(
                color = animatedColor,
                startAngle = -90f,
                sweepAngle = animatedProgress * 360f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        // Inner Content embedded inside the clock
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            when {
                isWin -> {
                    Text(
                        text = "WON",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = StatGreen,
                        letterSpacing = 1.sp
                    )
                    recentTrade?.let { trade ->
                        Text(
                            text = "+$${String.format(Locale.US, "%.2f", trade.netResult)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = StatGreen
                        )
                        Text(
                            text = "Exit: ${trade.exitDigit}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF86EFAC)
                        )
                    } ?: run {
                        Text(
                            text = "PROFIT",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = StatGreen
                        )
                    }
                }
                isLoss -> {
                    Text(
                        text = "LOST",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = StatRed,
                        letterSpacing = 1.sp
                    )
                    recentTrade?.let { trade ->
                        Text(
                            text = "-$${String.format(Locale.US, "%.2f", trade.stake)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = StatRed
                        )
                        Text(
                            text = "Exit: ${trade.exitDigit}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFFCA5A5)
                        )
                    } ?: run {
                        Text(
                            text = "LOSS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = StatRed
                        )
                    }
                }
                isTradeActive -> {
                    Text(
                        text = String.format(Locale.US, "00:%02d", remainingSeconds),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = VividYellow
                    )
                    Text(
                        text = "TICK LOCK",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                        color = TextSecondary
                    )
                }
                else -> {
                    Text(
                        text = String.format(Locale.US, "00:%02d", totalSeconds),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF8E95A5)
                    )
                    Text(
                        text = "STANDBY",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                        color = Color(0xFF6B7280)
                    )
                }
            }
        }
    }
}

@Composable
fun SimpleLineChart(
    points: List<Double>,
    modifier: Modifier = Modifier,
    lineColor: Color = VividYellow
) {
    if (points.isEmpty()) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(90.dp)
            .background(Color(0xFF13151D), RoundedCornerShape(10.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
            .padding(8.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val min = points.minOrNull() ?: 0.0
            val max = points.maxOrNull() ?: 1.0
            val range = if (max - min == 0.0) 1.0 else max - min

            val spacing = size.width / (points.size - 1).coerceAtLeast(1)

            for (i in 0 until points.size - 1) {
                val y1 = size.height - (((points[i] - min) / range) * size.height).toFloat()
                val y2 = size.height - (((points[i + 1] - min) / range) * size.height).toFloat()

                drawLine(
                    color = lineColor,
                    start = Offset(i * spacing, y1),
                    end = Offset((i + 1) * spacing, y2),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}
