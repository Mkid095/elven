package com.tickedge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tickedge.auth.AuthState
import com.tickedge.ui.theme.SurfaceDarkCard
import com.tickedge.ui.theme.TextPrimary
import com.tickedge.ui.theme.TextSecondary
import com.tickedge.ui.theme.VividYellow

/**
 * Login screen. Driven entirely by the app's [AuthState] (no scattered booleans).
 * "Continue with Deriv" opens the Deriv OAuth flow (or the dev PAT fallback when OAuth is not
 * configured).
 */
@Composable
fun AuthScreen(
    authState: AuthState,
    onContinueWithDeriv: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D0E12))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(VividYellow, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "E", fontSize = 38.sp, fontWeight = FontWeight.Black, color = Color(0xFF101216))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "ELVEN", fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp, color = VividYellow)
        Text(text = "Real-time Deriv tick analysis & trading", fontSize = 13.sp, color = TextSecondary)

        Spacer(modifier = Modifier.height(32.dp))

        when (val state = authState) {
            is AuthState.Authenticated -> Unit // parent switches to the main app

            is AuthState.Authenticating, is AuthState.Reconnecting -> {
                CircularProgressIndicator(color = VividYellow, modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "Connecting to Deriv…", fontSize = 14.sp, color = TextSecondary)
            }

            is AuthState.SessionExpired -> {
                Text(
                    text = "Your session expired. Reconnect to Deriv to continue.",
                    fontSize = 14.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(16.dp))
                CtaButton(onClick = onContinueWithDeriv, label = "Reconnect")
            }

            is AuthState.AuthenticationError -> {
                Text(
                    text = state.message,
                    fontSize = 14.sp,
                    color = Color(0xFFFF4757)
                )
                Spacer(modifier = Modifier.height(16.dp))
                CtaButton(onClick = onContinueWithDeriv, label = "Try Again")
            }

            is AuthState.Unauthenticated -> {
                Text(
                    text = "Connect your Deriv account to see your markets, balances and trades.",
                    fontSize = 14.sp,
                    color = TextSecondary,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                CtaButton(onClick = onContinueWithDeriv, label = "Continue with Deriv")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "By continuing you agree to the Deriv API terms.",
                fontSize = 11.sp,
                color = TextSecondary.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun CtaButton(onClick: () -> Unit, label: String) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = VividYellow, contentColor = Color(0xFF101216)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .testTag("deriv_continue_button")
    ) {
        Text(text = label, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}
