package com.tickedge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tickedge.auth.AccountSlotRegistry
import com.tickedge.auth.DerivSlot
import com.tickedge.ui.theme.BorderDark
import com.tickedge.ui.theme.StatRed
import com.tickedge.ui.theme.SurfaceDarkCard
import com.tickedge.ui.theme.TextPrimary
import com.tickedge.ui.theme.TextSecondary
import com.tickedge.ui.theme.VividYellow

/**
 * The profile button that opens the account manager.
 *
 * An avatar rather than a labelled button, because it sits in the header where there is no room
 * for text and the user recognises the gesture from every other app they use.
 */
@Composable
fun ProfileButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .background(Color(0xFF232734), CircleShape)
            .border(1.dp, BorderDark, CircleShape)
            .clickable { onClick() }
            .testTag("profile_button"),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = "Your Deriv accounts",
            tint = VividYellow,
            modifier = Modifier.size(19.dp)
        )
    }
}

/**
 * The Deriv accounts this device is signed in to.
 *
 * Each account is a separate login with its own credentials and its own trade history, so the
 * account number is what the list shows: it is the thing the user recognises, and it is what the
 * history is filed under.
 *
 * The cap is stated on the button rather than enforced silently. A user who has reached three
 * accounts and taps "Add another" needs to be told why nothing happens.
 */
@Composable
fun AccountManagerSheet(
    slots: List<DerivSlot>,
    activeSlotId: String,
    onSelect: (String) -> Unit,
    onAddAccount: () -> Unit,
    onSignOut: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val atLimit = slots.size >= AccountSlotRegistry.MAX_ACCOUNTS

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceDarkCard, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .padding(20.dp)
    ) {
        // The close affordance: the sheet is a floating panel, and a panel needs its own way out
        // — it must not depend on the user remembering it can be dismissed some other way.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Your Deriv accounts",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = TextSecondary,
                modifier = Modifier
                    .size(32.dp)
                    .padding(2.dp)
                    .clickable { onClose() }
                    .testTag("account_manager_close")
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "Each account keeps its own trades. Switching accounts switches the history " +
                "with it.",
            fontSize = 11.sp,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(14.dp))

        slots.forEach { slot ->
            val isActive = slot.id == activeSlotId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .background(Color(0xFF171A24), RoundedCornerShape(10.dp))
                    .border(
                        1.dp,
                        if (isActive) VividYellow else BorderDark,
                        RoundedCornerShape(10.dp)
                    )
                    .clickable { onSelect(slot.id) }
                    .padding(12.dp)
                    .testTag("account_slot_${slot.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = slot.displayLabel,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isActive) VividYellow else TextPrimary
                    )
                    if (slot.accountNumber.isNotBlank()) {
                        Text(
                            text = "Account ${slot.accountNumber}",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
                if (isActive) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "In use",
                        tint = VividYellow,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Text(
                        text = "Sign out",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = StatRed,
                        modifier = Modifier
                            .clickable { onSignOut(slot.id) }
                            .padding(6.dp)
                            .testTag("sign_out_slot_${slot.id}")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Add another account.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF171A24), RoundedCornerShape(10.dp))
                .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
                .clickable(enabled = !atLimit) { onAddAccount() }
                .padding(12.dp)
                .testTag("add_account_button"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = if (atLimit) TextSecondary else VividYellow,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = if (atLimit) {
                    "You can be signed in to ${AccountSlotRegistry.MAX_ACCOUNTS} accounts at once"
                } else {
                    "Add another Deriv account"
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (atLimit) TextSecondary else VividYellow
            )
        }
    }
}
