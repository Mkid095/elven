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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tickedge.auth.AuthState
import com.tickedge.ui.theme.TextPrimary
import com.tickedge.ui.theme.TextSecondary
import com.tickedge.ui.theme.VividYellow
import com.tickedge.ui.viewmodel.MainViewModel.SyncState

/**
 * Login screen. Two identities, deliberately kept separate — and deliberately in this order:
 *
 *  1. **The TickEdge account first** — the person's own, signed in with an email and a code.
 *     This is the identity the settings and trade history are filed under, so it is what the
 *     app remembers across phones and reinstalls. Free, and the first thing a new user does.
 *  2. **Deriv second** — the trading account. Without it nothing on the app works, because the
 *     markets, balances and orders all come from it. It is a wallet under the person, not the
 *     identity.
 *
 * Signing in with the email code is what the user is asked to do before anything else. The
 * Deriv step only appears once the TickEdge account is signed in — by which point the person
 * the data belongs to is already known, and connecting a Deriv account just gives them more
 * money to trade with.
 */
@Composable
fun AuthScreen(
    authState: AuthState,
    onContinueWithDeriv: () -> Unit,
    /** Whether the person already has a TickEdge cloud account on this device. */
    cloudSignedIn: Boolean = false,
    /** The email the cloud account was created with, when [cloudSignedIn]. */
    cloudEmail: String? = null,
    /** What the cloud sign-in is doing or why it has failed, shown next to the code field. */
    cloudStatus: SyncState =
        SyncState.Unavailable("Not signed in yet."),
    /** Sends the sign-in code to an email. */
    onSendMagicCode: (String) -> Unit = {},
    /** Verifies the code and signs the cloud account in. */
    onVerifyCode: (String, String) -> Unit = { _, _ -> },
    /** Creates the account and registers the person's details with it. */
    onSignUpVerify: (String, String, String, String, String) -> Unit = { _, _, _, _, _ -> },
    /** Signs the device out of the TickEdge account (Deriv sign-in is kept). */
    onCloudSignOut: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    // Registration fields, owned here so they survive switching between the two paths.
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }

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

        Spacer(modifier = Modifier.height(24.dp))

        if (!cloudSignedIn) {
            // Step one: the person's own account. Nothing else on this screen until it exists,
            // because everything the app remembers from here on is filed under it.
            CloudAccountCard(
                signedIn = false,
                email = null,
                status = cloudStatus,
                emailText = email,
                codeText = code,
                codeSent = codeSent,
                onEmailChange = { email = it },
                onCodeChange = { code = it },
                onSendCode = {
                    codeSent = true
                    onSendMagicCode(email)
                },
                onVerify = { onVerifyCode(email, code) },
                onSignUpVerify = { f, l, u -> onSignUpVerify(f, l, u, email, code) },
                onSignOut = {},
                firstName = firstName,
                lastName = lastName,
                username = username,
                onFirstNameChange = { firstName = it },
                onLastNameChange = { lastName = it },
                onUsernameChange = { username = it },
                modifier = Modifier.fillMaxWidth().testTag("cloud_card_auth")
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Once you are in, you will connect a Deriv account to start trading.",
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            // Step two: the trading account. The person is already identified, so this only says
            // which money trades on it.
            when (val state = authState) {
                is AuthState.Authenticated -> Unit // parent switches to the main app

                is AuthState.Authenticating, is AuthState.Reconnecting -> {
                    CircularProgressIndicator(color = VividYellow, modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "Connecting to Deriv…", fontSize = 14.sp, color = TextSecondary)
                }

                is AuthState.SessionExpired -> {
                    Text(
                        text = "Your Deriv session expired. Reconnect to continue.",
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
                    CtaButton(onClick = onContinueWithDeriv, label = "Connect your Deriv account")
                }
            }

            // Who is signed in, in one line — and how to get off it without losing the Deriv login.
            Spacer(modifier = Modifier.height(20.dp))
            CloudAccountCard(
                signedIn = true,
                email = cloudEmail,
                status = cloudStatus,
                emailText = "",
                codeText = "",
                codeSent = false,
                onEmailChange = {},
                onCodeChange = {},
                onSendCode = {},
                onVerify = {},
                onSignUpVerify = { _, _, _ -> },
                onSignOut = onCloudSignOut,
                firstName = "",
                lastName = "",
                username = "",
                onFirstNameChange = {},
                onLastNameChange = {},
                onUsernameChange = {},
                modifier = Modifier.fillMaxWidth()
            )

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
