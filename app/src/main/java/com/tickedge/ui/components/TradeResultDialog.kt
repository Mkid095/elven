package com.tickedge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.tickedge.data.model.SimulatedTrade
import com.tickedge.data.model.TradeOutcome
import com.tickedge.ui.theme.BorderDark
import com.tickedge.ui.theme.StatGreen
import com.tickedge.ui.theme.StatRed
import com.tickedge.ui.theme.SurfaceDarkCard
import com.tickedge.ui.theme.TextPrimary
import com.tickedge.ui.theme.TextSecondary
import com.tickedge.ui.theme.VividYellow
import java.util.Locale

@Composable
fun TradeResultDialog(
    trade: SimulatedTrade,
    onDismiss: () -> Unit
) {
    val isWin = trade.outcome == TradeOutcome.WON

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = SurfaceDarkCard,
            border = androidx.compose.foundation.BorderStroke(1.dp, if (isWin) StatGreen else StatRed),
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Win / Loss Icon
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .background(if (isWin) Color(0x2200C076) else Color(0x22FF4757), CircleShape)
                        .border(1.5.dp, if (isWin) StatGreen else StatRed, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isWin) Icons.Default.Check else Icons.Default.Close,
                        contentDescription = null,
                        tint = if (isWin) StatGreen else StatRed,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Text(
                    text = if (isWin) "Simulated Contract Won!" else "Simulated Contract Lost",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isWin) StatGreen else StatRed
                )

                // Result Box
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF13151D), RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Market:", fontSize = 12.sp, color = TextSecondary)
                        Text(text = trade.marketName, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Contract Type:", fontSize = 12.sp, color = TextSecondary)
                        Text(text = trade.tradeType.displayName, fontSize = 12.sp, color = TextPrimary)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Target / Exit Digit:", fontSize = 12.sp, color = TextSecondary)
                        Text(text = "${trade.targetDigit} vs Exit ${trade.exitDigit}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = VividYellow)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Simulated Stake:", fontSize = 12.sp, color = TextSecondary)
                        Text(text = String.format(Locale.US, "$%.2f", trade.stake), fontSize = 12.sp, color = TextPrimary)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Net Result:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                        val pnlSign = if (isWin) "+" else ""
                        Text(
                            text = String.format(Locale.US, "%s$%.2f", pnlSign, trade.netResult),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            color = if (isWin) StatGreen else StatRed
                        )
                    }
                }

                Text(
                    text = "* Non-executing hypothetical paper settlement",
                    fontSize = 10.sp,
                    color = TextSecondary
                )

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VividYellow,
                        contentColor = Color(0xFF101216)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) {
                    Text(text = "Continue", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}
