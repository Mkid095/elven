package com.tickedge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.tickedge.auth.DerivOAuthClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Transparent trampoline that receives the Deriv OAuth redirect (`elven://oauth/callback`),
 * completes the code->token exchange (persisting the token to encrypted storage), and returns to
 * the app. The main screen re-reads the stored session on resume.
 */
class DerivOAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent.data
        if (data != null) {
            val client = DerivOAuthClient(applicationContext, CoroutineScope(SupervisorJob() + Dispatchers.Main))
            client.handleCallback(data)
        }
        val launcher = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(launcher)
        finish()
    }
}
