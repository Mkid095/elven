package com.tickedge.data.deriv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin REST client for the authenticated Deriv V1 options API.
 *
 * Auth is a bearer token supplied via [tokenProvider] (a per-user OAuth access token in
 * production, or the embedded dev PAT as a fallback) plus the `Deriv-App-ID` header.
 */
class DerivRestClient(
    private val appId: String,
    private val tokenProvider: () -> String?
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://api.derivws.com"

    private fun requireToken(): String =
        requireNotNull(tokenProvider()) { "No Deriv access token available (OAuth or dev PAT)" }

    /** GET /trading/v1/options/accounts -> the caller's demo + real accounts with live balances. */
    suspend fun fetchAccounts(): List<DerivAccount> = withContext(Dispatchers.IO) {
        val token = requireToken()
        val req = Request.Builder()
            .url("$baseUrl/trading/v1/options/accounts")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Deriv-App-ID", appId)
            .build()

        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            require(resp.code in 200..299) { "accounts HTTP ${resp.code}: ${text.take(200)}" }
            val arr = JSONObject(text).getJSONArray("data")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DerivAccount(
                    id = o.getString("account_id"),
                    kind = if (o.optString("account_type").equals("demo", ignoreCase = true))
                        DerivAccountKind.DEMO else DerivAccountKind.REAL,
                    currency = o.optString("currency", "USD"),
                    balance = o.optDouble("balance", 0.0),
                    status = o.optString("status", "active")
                )
            }
        }
    }

    /**
     * POST /trading/v1/options/accounts/{id}/otp -> the per-account trading WebSocket URL.
     * Returns the `data.url` (wss://.../trading/v1/options/ws/{demo|real}?otp=...).
     */
    suspend fun fetchTradingWsUrl(accountId: String): String = withContext(Dispatchers.IO) {
        val token = requireToken()
        val req = Request.Builder()
            .url("$baseUrl/trading/v1/options/accounts/$accountId/otp")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Deriv-App-ID", appId)
            .build()

        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            require(resp.code in 200..299) { "otp HTTP ${resp.code}: ${text.take(200)}" }
            JSONObject(text).getJSONObject("data").getString("url")
        }
    }
}
