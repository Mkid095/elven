package com.tickedge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tickedge.ui.theme.BorderDark
import com.tickedge.ui.theme.StatGreen
import com.tickedge.ui.theme.StatRed
import com.tickedge.ui.theme.TextPrimary
import com.tickedge.ui.theme.TextSecondary
import com.tickedge.ui.theme.VividYellow
import java.util.Locale

/**
 * One row of the Martingale recovery risk table on the Trade Settings screen: the stake for a
 * given step, how much capital is cumulatively at risk, and the two outcomes.
 */
@Composable
fun RecoveryRiskRow(
    stepNumber: Int,
    contractType: String,
    stake: Double,
    cumulativeRisk: Double,
    ifWinText: String,
    ifLossText: String,
    isBase: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isBase) Color(0xFF171B26) else Color(0xFF141620), RoundedCornerShape(10.dp))
            .border(1.dp, if (isBase) Color(0xFF2C3245) else BorderDark, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(if (isBase) Color(0xFF2E3854) else VividYellow, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "STEP $stepNumber",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isBase) TextPrimary else Color(0xFF101216)
                        )
                    }
                    Text(
                        text = contractType,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
                Text(
                    text = String.format(Locale.US, "Stake: $%.2f", stake),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = VividYellow
                )
            }

            Text(
                text = String.format(Locale.US, "Cumulative Capital at Risk: $%.2f", cumulativeRisk),
                fontSize = 10.sp,
                color = TextSecondary
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(text = "✓ If Win:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = StatGreen)
                Text(text = ifWinText, fontSize = 10.sp, color = StatGreen, lineHeight = 14.sp)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(text = "✗ If Loss:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = StatRed)
                Text(text = ifLossText, fontSize = 10.sp, color = TextSecondary, lineHeight = 14.sp)
            }
        }
    }
}