package com.tickedge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tickedge.ui.theme.BorderDark
import com.tickedge.ui.theme.SurfaceDarkCard
import com.tickedge.ui.theme.TextPrimary
import com.tickedge.ui.theme.TextSecondary
import com.tickedge.ui.theme.VividYellow
import com.tickedge.ui.viewmodel.MainViewModel.SyncState

/**
 * The TickEdge account card: the free account that carries the user's settings and trade
 * history to their next phone.
 *
 * Two paths, and the card says which one it is:
 *
 *  - **Create account** — a new person. They give a first name, last name, username and email,
 *    and the code that arrives by email *creates* the account. That registration is what the
 *    platform knows about them from now on, so it is asked for here, once, at creation.
 *  - **Sign in** — a person who has an account. Email and code only; the code is the proof the
 *    email is theirs, and the same email on any phone finds the same records.
 *
 * The card appears on both the sign-in screen and Settings, because signing out must be
 * undoable from inside the app.
 */
@Composable
fun CloudAccountCard(
    signedIn: Boolean,
    email: String?,
    status: SyncState,
    emailText: String,
    codeText: String,
    codeSent: Boolean,
    onEmailChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onSendCode: () -> Unit,
    /** Sign-in path: an account that already exists. */
    onVerify: () -> Unit = {},
    /** Create-account path: verifies the code and registers the person with the new account. */
    onSignUpVerify: (firstName: String, lastName: String, username: String) -> Unit = { _, _, _ -> },
    onSignOut: () -> Unit = {},
    /** Registration fields, bound and owned by the caller so they survive path switches. */
    firstName: String = "",
    lastName: String = "",
    username: String = "",
    onFirstNameChange: (String) -> Unit = {},
    onLastNameChange: (String) -> Unit = {},
    onUsernameChange: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Which path the card is on. A new person starts on "Create account"; the default keeps a
    // blank card honest — the first thing a new phone is ever asked to do is create the
    // account.
    var signUp by remember { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceDarkCard, RoundedCornerShape(14.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "TickEdge account",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = "FREE",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = VividYellow,
                modifier = Modifier
                    .background(Color(0xFF232734), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag("cloud_free_badge")
            )
        }

        if (signedIn) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = email ?: "Signed in",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f).testTag("cloud_account_line")
                )
                TextButton(
                    onClick = onSignOut,
                    modifier = Modifier.testTag("cloud_sign_out_button")
                ) {
                    Text(text = "Sign out", color = TextSecondary, fontSize = 12.sp)
                }
            }
            return
        }

        Text(
            text = "Your settings and trade history are kept under your email, so the same " +
                "email on another phone finds the same records. Nothing is charged for it.",
            fontSize = 12.sp,
            color = TextSecondary
        )

        // The path switcher: a new person registers, a returning one signs in. The two are
        // separate because creating an account owes the platform a name, and signing in does
        // not.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF12141C), RoundedCornerShape(10.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .background(if (signUp) VividYellow else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { signUp = true }
                    .padding(vertical = 7.dp)
                    .testTag("cloud_signup_tab"),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Create account",
                    fontSize = 12.sp,
                    fontWeight = if (signUp) FontWeight.Bold else FontWeight.Medium,
                    color = if (signUp) Color(0xFF101216) else TextSecondary
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .background(if (!signUp) VividYellow else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { signUp = false }
                    .padding(vertical = 7.dp)
                    .testTag("cloud_signin_tab"),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Sign in",
                    fontSize = 12.sp,
                    fontWeight = if (!signUp) FontWeight.Bold else FontWeight.Medium,
                    color = if (!signUp) Color(0xFF101216) else TextSecondary
                )
            }
        }

        if (signUp) {
            OutlinedTextField(
                value = firstName,
                onValueChange = onFirstNameChange,
                label = { Text("First name") },
                singleLine = true,
                colors = fieldColors(),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().testTag("cloud_first_name_input")
            )
            OutlinedTextField(
                value = lastName,
                onValueChange = onLastNameChange,
                label = { Text("Last name") },
                singleLine = true,
                colors = fieldColors(),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().testTag("cloud_last_name_input")
            )
            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = { Text("Username") },
                singleLine = true,
                colors = fieldColors(),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().testTag("cloud_username_input")
            )
        }

        OutlinedTextField(
            value = emailText,
            onValueChange = onEmailChange,
            label = { Text("Email address") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            colors = fieldColors(),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().testTag("cloud_email_input")
        )

        if (codeSent) {
            Text(
                text = "We sent a 6-digit code to $emailText",
                fontSize = 12.sp,
                color = VividYellow
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = codeText,
                    onValueChange = { onCodeChange(it.filter { c -> c.isDigit() }.take(6)) },
                    label = { Text("Code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = fieldColors(),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).testTag("cloud_code_input")
                )
                Button(
                    onClick = {
                        if (signUp) onSignUpVerify(firstName, lastName, username) else onVerify()
                    },
                    enabled = codeText.length == 6,
                    colors = buttonColors(),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(52.dp).testTag("cloud_verify_button")
                ) {
                    Text(if (signUp) "Create account" else "Sign in", fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Button(
                onClick = onSendCode,
                enabled = emailText.contains('@'),
                colors = buttonColors(),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("cloud_send_code_button")
            ) {
                Text("Send me a code", fontWeight = FontWeight.Bold)
            }
        }

        // The cloud status belongs here, next to the form that produces it.
        when (status) {
            is SyncState.Failed -> Text(
                text = status.reason,
                fontSize = 12.sp,
                color = Color(0xFFFF4757)
            )
            is SyncState.Connecting, is SyncState.Syncing -> Text(
                text = "Working…",
                fontSize = 12.sp,
                color = TextSecondary
            )
            else -> Unit
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = VividYellow,
    unfocusedBorderColor = BorderDark,
    focusedLabelColor = VividYellow,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedContainerColor = Color(0xFF13151D),
    unfocusedContainerColor = Color(0xFF13151D)
)

@Composable
private fun buttonColors() = ButtonDefaults.buttonColors(
    containerColor = VividYellow,
    contentColor = Color(0xFF101216)
)
