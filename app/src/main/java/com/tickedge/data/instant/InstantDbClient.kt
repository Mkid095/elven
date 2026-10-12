package com.tickedge.data.instant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal client for a self-hosted InstantDB (FIDScript) backend, used for cross-device,
 * user-scoped persistence of settings/history.
 *
 * Requires (via BuildConfig, from .env):
 *  - INSTANT_APP_ID: the FIDScript/InstantDB app id
 *  - INSTANT_API_BASE: default https://apiinstant.fidscript.com
 *  - a per-user auth token (provided at runtime, e.g. from the FIDScript magic-code sign-in)
 *
 * The schema (user_settings / trade_history entities scoped by user id) is pushed to the backend
 * via the instant-self MCP (`push_schema`), not from the app. See docs/.
 */
class InstantDbClient(
    private val appId: String,
    private val apiBase: String,
    private val userTokenProvider: () -> String?
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun requireToken(): String =
        requireNotNull(userTokenProvider()) { "No InstantDB user token" }

    /** Run an InstaQL query against the backend. */
    suspend fun query(instq: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$apiBase/api/v1/$appId/query")
            .post(instq.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${requireToken()}")
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            require(resp.code in 200..299) { "InstantDB query HTTP ${resp.code}: ${text.take(200)}" }
            JSONObject(text)
        }
    }

    /** Apply a transaction (create/update/delete). */
    suspend fun transact(tx: JSONArray): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$apiBase/api/v1/$appId/transact")
            .post(tx.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${requireToken()}")
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            require(resp.code in 200..299) { "InstantDB transact HTTP ${resp.code}: ${text.take(200)}" }
            JSONObject(text)
        }
    }

    /** Whether the backend is configured for use. */
    fun isConfigured(): Boolean = appId.isNotBlank()
}
