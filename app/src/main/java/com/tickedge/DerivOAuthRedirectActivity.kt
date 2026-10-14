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
 * Two redirect forms can match this activity's intent filters, and both are handled identically:
 *  - `https://elven.nextmavens.com/oauth/callback?code=...` — verified Android App Link, and
 *    the only path Deriv will actually use: this exact URI is registered on the Deriv app,
 *    while `elven://oauth/callback` is NOT, so Deriv rejects it with `invalid_request` before
 *    any redirect happens. The custom-scheme filter is kept only so the app can still be opened
 *    manually; it is not a working fallback for sign-in.
 *
 * Because the App Link is the sole route, assetlinks.json must keep publishing the fingerprint
 * of the certificate that signed the installed build, or Android will not route the redirect here.
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

    /**
     * The one-time authorization code can only be redeemed once, and [finish] is not idempotent.
     * [onCreate] and [onNewIntent] can both deliver the same callback (activity recreation, OEM
     * intent redelivery), which previously ran the code->token exchange twice and called finish
     * twice — the exchange raced against its own `clearPendingAuth`, producing intermittent
     * failures and `W ActivityTaskManager: Duplicate finish request`.
     */
    private val redirectHandled = java.util.concurrent.atomic.AtomicBoolean(false)
    private val finished = java.util.concurrent.atomic.AtomicBoolean(false)

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
        // Consume the guard only once there is a real redirect to process, so a null-intent
        // launch does not block the actual callback that may follow via onNewIntent.
        if (!redirectHandled.compareAndSet(false, true)) return
        val client = DerivOAuthClient.share(applicationContext, scope)
        scope.launch {
            // Failure is already reflected in authState; either way return to MainActivity.
            withContext(NonCancellable) { client.handleCallback(data) }
            finishAndGoToMain()
        }
    }

    private fun finishAndGoToMain() {
        if (!finished.compareAndSet(false, true)) return
        val launcher = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(launcher)
        finish()
    }
}
