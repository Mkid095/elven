package com.tickedge.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tickedge.ui.theme.StatGreen
import com.tickedge.ui.theme.TextSecondary
import com.tickedge.ui.theme.VividYellow

/**
 * The balance's freshness control: says whether the figure is live, and lets the user force a sync.
 *
 * Three states, all distinguishable at a glance, because the difference between them decides whether
 * the number on screen can be trusted:
 *
 *  - **Live** (green) — the account's socket is pushing, so the figure changes the instant money
 *    does. This is the default and the normal case.
 *  - **Syncing** (yellow, spinning) — a sync is in flight. The rotation is the only feedback the
 *    user gets that a tap registered; without it the tap looks ignored.
 *  - **Tap to sync** (grey) — not live, so the figure is only as fresh as the last poll. Tapping
 *    repairs the connection rather than merely re-reading a number already known to be stale.
 *
 * Applies to demo and real alike: both accounts hold a socket, so both report live, and this chip
 * describes whichever account is on screen.
 */
@Composable
fun BalanceSyncChip(
    isLive: Boolean,
    isSyncing: Boolean,
    onSync: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (label, tint) = when {
        isSyncing -> "Syncing…" to VividYellow
        isLive -> "Live" to StatGreen
        else -> "Tap to sync" to TextSecondary
    }

    // One unconditional transition: branching on `isSyncing` around `remember` would be unsafe,
    // since a composable called conditionally is not stable across recompositions.
    //
    // The tween keeps a real duration even while idle. Do NOT "park" it at 0ms to stop the spin.
    // An infinite repeat spec derives its repeat count by dividing by the play time, so a zero
    // duration throws ArithmeticException on the very first frame (it crashed the app once).
    // Idling is handled by pinning the angle instead, which leaves the transition itself untouched.
    val spin by rememberInfiniteTransition(label = "balanceSync").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SYNC_SPIN_MS),
            repeatMode = RepeatMode.Restart
        ),
        label = "balanceSyncRotation"
    )
    val rotation = if (isSyncing) spin else 0f

    val clickable = !isSyncing

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = modifier
            .background(tint.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
            .border(1.dp, tint.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .then(if (clickable) Modifier.clickable(onClick = onSync) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("balance_sync_chip")
            // The icon alone reads as decorative; without this the control's purpose and current
            // state are invisible to a screen reader.
            .semantics {
                contentDescription = when {
                    isSyncing -> "Syncing balance"
                    isLive -> "Balance is live. Double tap to sync now."
                    else -> "Balance is not live. Double tap to sync now."
                }
            }
    ) {
        Icon(
            imageVector = Icons.Default.Sync,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp).rotate(rotation)
        )
        Text(text = label, fontSize = 11.sp, color = tint)
    }
}

/** How long one full turn of the sync icon takes. Fast enough to read as "working", not a blur. */
private const val SYNC_SPIN_MS = 900