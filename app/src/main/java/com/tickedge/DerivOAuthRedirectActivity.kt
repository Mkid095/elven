package com.tickedge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.tickedge.auth.DerivOAuthClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transparent trampoline that receives the Deriv OAuth redirect, completes the code->token
 * exchange (persisting the token to encrypted storage), and returns to the app.
 *
 * Two redirect forms reach this activity, and both are handled identically:
 *  - `https://elven.nextmavens.com/oauth/callback?code=...` — verified Android App Link, the
 *    primary path. Deriv redirects here and Android opens the app directly.
 *  - `elven://oauth/callback?code=...` — custom-scheme fallback, used only when App Link
 *    verification fails and the browser lands on elven-web's callback page instead.
 *
 * ## Lifecycle Safety
 *
 * Uses [DerivOAuthClient.share] — the same singleton instance used by [MainViewModel].
 * Pending auth state is recovered from encrypted storage, so this works even if the
 * initiating process was killed before the callback arrived.
 *
 * [DerivOAuthClient.handleCallback] is awaited as a suspend function, so the activity does NOT
 * finish until the token exchange is confirmed. [NonCancellable] ensures the HTTP exchange
 * completes even if the activity enters a finishing state.
 */
class DerivOAuthRedirectActivity : Activity() {

    /**
     * This is a plain [android.app.Activity], not a `ComponentActivity`, so it is **not** a
     * [androidx.lifecycle.LifecycleOwner] and `lifecycleScope` does not resolve. An explicit
     * scope is used instead, cancelled in [onDestroy] so a destroyed activity leaks no work.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleRedirect(intent?.data)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * With a singleTop/singleTask launch mode a repeat launch while this instance is still alive
     * is delivered here rather than to [onCreate]. Without this override the new intent's data
     * would be dropped and the callback would silently hang.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRedirect(intent?.data)
    }

    private fun handleRedirect(data: android.net.Uri?) {
        if (data == null) {
            finishAndGoToMain()
            return
        }
        val client = DerivOAuthClient.share(applicationContext, scope)
        scope.launch {
            // Failure is already reflected in authState; either way return to MainActivity.
            withContext(NonCancellable) { client.handleCallback(data) }
            finishAndGoToMain()
        }
    }

    private fun finishAndGoToMain() {
        val launcher = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(launcher)
        finish()
    }
}
