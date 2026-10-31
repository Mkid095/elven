package com.tickedge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tickedge.ui.theme.BorderDark
import com.tickedge.ui.theme.StatRed
import com.tickedge.ui.theme.SurfaceDarkCard
import com.tickedge.ui.theme.TextPrimary
import com.tickedge.ui.theme.TextSecondary

/**
 * The one-time warning shown before the app trades a live account.
 *
 * Replaces a per-order confirmation. A dialog on every order is not a safeguard — it is a tax on
 * every order, and a user who has already understood the risk learns to dismiss it without reading
 * it, so later orders get none of the protection while paying all of the cost. Asking once, at the
 * moment the account changes, puts the same information where it can still be read.
 *
 * The wording is deliberately specific about what is and is not being promised. Nothing here
 * suggests the strategy is profitable, and the user is asked to accept a loss they may not be able
 * to afford — which is the actual decision being made.
 */
@Composable
fun LiveRiskDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xCC05060A))
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDarkCard, RoundedCornerShape(16.dp))
                .border(1.dp, StatRed, RoundedCornerShape(16.dp))
                .padding(20.dp)
                .testTag("live_risk_dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "You are switching to your LIVE account",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = StatRed
            )

            Text(
                text = "Every order from here spends real money. This is a decision you should be " +
                    "able to afford to be wrong about.",
                fontSize = 13.sp,
                color = TextPrimary
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RiskPoint(
                    "Your losses are real losses. A run can lose money you cannot get back, and a " +
                        "recovery ladder increases each stake to chase one."
                )
                RiskPoint(
                    "Results depend on the strategy's settings and on the market. Past results " +
                        "shown elsewhere in this app do not predict what happens next."
                )
                RiskPoint(
                    "The stop loss and daily limit bound a run, but they are not a guarantee. " +
                        "Trading can lose more than you expect if the market moves against a stake."
                )
                RiskPoint(
                    "You will not be asked to confirm each order. Once you accept, every order " +
                        "this session places goes straight to your live balance."
                )
            }

            Text(
                text = "You can switch back to the demo account at any time from this page.",
                fontSize = 11.sp,
                color = TextSecondary
            )

            Box(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TextButton(
                    onClick = onDecline,
                    modifier = Modifier.weight(1f).testTag("live_risk_decline")
                ) {
                    Text(text = "Stay on demo", fontSize = 13.sp, color = TextSecondary)
                }
                Button(
                    onClick = onAccept,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StatRed,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.weight(1f).testTag("live_risk_accept")
                ) {
                    Text(
                        text = "I understand, use live",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun RiskPoint(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "•", fontSize = 12.sp, color = StatRed)
        Text(text = text, fontSize = 12.sp, color = TextSecondary)
    }
}
