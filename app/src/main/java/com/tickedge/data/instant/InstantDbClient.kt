package com.tickedge.data.instant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * FIDScript client for cross-device sync of user settings and trade history.
 *
 * ## Wire protocol (verified against the live server 2026-10-14)
 *
 * Auth is a **public** FIDScript session obtained via [FidscriptSession] from a Deriv ID token.
 * The session's `refresh_token` is the bearer credential for both endpoints below.
 *
 * ```
 * POST {apiBase}/runtime/{appId}/transact
 *   headers: Content-Type, app-id, Authorization: Bearer <refresh_token>
 *   body:    { "tx-steps": [ [op, entityId, attrId, value], ... ] }
 *   ->       { "tx-id": "...", "isn": "2/0/..." }
 *
 * POST {apiBase}/runtime/framework/query
 *   headers: Content-Type, app-id, Authorization: Bearer <refresh_token>
 *            (plus `Instant-ISN: <isn>` for a deterministic read-your-writes read)
 *   body:    { "query": { "<namespace>": {} } }
 *   ->       { "result": [ { "data": { "datalog-result": { "join-rows": [[...]] } } } ] }
 * ```
 *
 * Each join-row is `[entityId, attrId, value, updatedAtMs]`. There is no entity/attribute
 * envelope — values are grouped by the caller using [FidscriptAttrs].
 *
 * ## Two things that will silently break this client
 *
 * 1. **A new entity has no `userId` yet.** Row permissions are `auth.id == data.userId`, so a
 *    write that omits `userId` is denied. [upsertUserSettings] always writes it first, in the
 *    same transaction.
 * 2. **Values must be scalars.** JSON objects and arrays are rejected by the server
 *    (`expected: instant.db.model.triple/value`). Map/structure fields must be flattened into
 *    typed attributes — which is also what makes them queryable.
 */
class InstantDbClient(
    private val appId: String,
    private val apiBase: String,
    private val session: FidscriptSession
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** A single attribute value read back from FIDScript. */
    data class Row(val entityId: String, val attrId: String, val value: Any?, val updatedAt: Long)

    /** Result of a transaction, including the ISN to pin the next read to. */
    data class TxnResult(val txId: String, val isn: String?)

    /** True when a FIDScript session exists and data ops can run. */
    fun isReady(): Boolean = session.isSignedIn() && appId.isNotBlank()

    // ------------------------------------------------------------------ reads

    /**
     * Query every row of a namespace for the current user.
     *
     * @param isn pass the [TxnResult.isn] from a preceding write to guarantee read-your-writes.
     */
    suspend fun query(namespace: String, isn: String? = null): Result<List<Row>> =
        withContext(Dispatchers.IO) {
            val token = session.refreshToken()
                ?: return@withContext Result.failure(NotSignedIn)

            val payload = JSONObject().put("query", JSONObject().put(namespace, JSONObject()))

            val builder = Request.Builder()
                .url("$apiBase/runtime/framework/query")
                .post(payload.toString().toRequestBody(JSON))
                .header("Content-Type", "application/json")
                .header("app-id", appId)
                .header("Authorization", "Bearer $token")

            if (!isn.isNullOrBlank()) builder.header("Instant-ISN", isn)

            try {
                http.newCall(builder.build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            IOException("FIDScript query failed (HTTP ${response.code}): ${summarize(body)}")
                        )
                    }
                    Result.success(decodeRows(body))
                }
            } catch (e: IOException) {
                Result.failure(e)
            }
        }

    // ----------------------------------------------------------------- writes

    /**
     * Apply a raw transaction.
     *
     * @param steps each is `[op, entityId, attrId, value]`; op is one of `deep-merge-triple`,
     *   `add-triple`, `retract-triple`, `delete-entity` (the latter two take fewer arguments).
     */
    suspend fun transact(steps: List<JSONArray>): Result<TxnResult> = withContext(Dispatchers.IO) {
        val token = session.refreshToken()
            ?: return@withContext Result.failure(NotSignedIn)

        val stepsArray = JSONArray()
        steps.forEach(stepsArray::put)

        val payload = JSONObject().put("tx-steps", stepsArray)

        val request = Request.Builder()
            .url("$apiBase/runtime/$appId/transact")
            .post(payload.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .header("app-id", appId)
            .header("Authorization", "Bearer $token")
            .build()

        try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("FIDScript transact failed (HTTP ${response.code}): ${summarize(body)}")
                    )
                }
                val json = JSONObject(body)
                Result.success(
                    TxnResult(
                        txId = json.optString("tx-id"),
                        isn = json.optString("isn").takeIf { it.isNotBlank() }
                    )
                )
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /**
     * Create or update this user's settings row.
     *
     * [userId] must be FIDScript's `auth.id` for the user (see [FidscriptSession.userId]) —
     * writing the Deriv app id instead produces a row the user can never read back.
     *
     * `userId` is written first in the same transaction so the row passes the permission check
     * on its very first write.
     */
    suspend fun upsertUserSettings(
        userId: String,
        fields: Map<String, Any?>,
        entityId: String = UUID.randomUUID().toString()
    ): Result<TxnResult> {
        val steps = mutableListOf(
            step(OP_MERGE, entityId, FidscriptAttrs.USER_SETTINGS_USERID, userId)
        )
        fields.forEach { (name, value) ->
            if (name == "userId" || value == null) return@forEach
            val attrId = userSettingsAttr(name)
                ?: return Result.failure(
                    IllegalArgumentException("Unknown user_settings attribute: $name")
                )
            steps += step(OP_MERGE, entityId, attrId, value)
        }
        steps += step(OP_MERGE, entityId, FidscriptAttrs.USER_SETTINGS_ID, entityId)
        return transact(steps)
    }

    /** Delete an entity, e.g. to clear a user's synced history. */
    suspend fun deleteEntity(entityId: String): Result<TxnResult> =
        transact(listOf(JSONArray().put(OP_DELETE).put(entityId)))

    /** Remove a single attribute from an entity. */
    suspend fun retractTriple(entityId: String, attrId: String): Result<TxnResult> =
        transact(listOf(JSONArray().put(OP_RETRACT).put(entityId).put(attrId)))

    // --------------------------------------------------------------- helpers

    private fun step(op: String, entityId: String, attrId: String, value: Any?): JSONArray =
        JSONArray().put(op).put(entityId).put(attrId).put(value)

    /** Map a `user_settings` field name to its validated attribute id. */
    private fun userSettingsAttr(name: String): String? = when (name) {
        "targetDigit" -> FidscriptAttrs.USER_SETTINGS_TARGETDIGIT
        "recoveryDigit" -> FidscriptAttrs.USER_SETTINGS_RECOVERYDIGIT
        "recoveryDirection" -> FidscriptAttrs.USER_SETTINGS_RECOVERYDIRECTION
        "stakeAmount" -> FidscriptAttrs.USER_SETTINGS_BASESTAKE
        "takeProfit" -> FidscriptAttrs.USER_SETTINGS_TAKEPROFIT
        "stopLoss" -> FidscriptAttrs.USER_SETTINGS_STOPLOSS
        "maxDailyLoss" -> FidscriptAttrs.USER_SETTINGS_MAXDAILYLOSS
        "maxMartingaleSteps" -> FidscriptAttrs.USER_SETTINGS_MAXMARTINGALESTEPS
        "realArmed" -> FidscriptAttrs.USER_SETTINGS_REALARMED
        "requirePerOrderConfirm" -> FidscriptAttrs.USER_SETTINGS_REQUIREPERORDERCONFIRM
        "contractType" -> FidscriptAttrs.USER_SETTINGS_CONTRACTTYPE
        "market" -> FidscriptAttrs.USER_SETTINGS_MARKET
        "activeAccount" -> FidscriptAttrs.USER_SETTINGS_ACTIVEACCOUNTKIND
        "accountId" -> FidscriptAttrs.USER_SETTINGS_ACCOUNTID
        "updatedAt" -> FidscriptAttrs.USER_SETTINGS_UPDATEDAT
        else -> null
    }

    /** Map a `trade_history` field name to its validated attribute id. */
    fun tradeHistoryAttr(name: String): String? = when (name) {
        "market" -> FidscriptAttrs.TRADE_HISTORY_MARKET
        "digit" -> FidscriptAttrs.TRADE_HISTORY_DIGIT
        "stake" -> FidscriptAttrs.TRADE_HISTORY_STAKE
        "net" -> FidscriptAttrs.TRADE_HISTORY_NET
        "outcome" -> FidscriptAttrs.TRADE_HISTORY_OUTCOME
        "currency" -> FidscriptAttrs.TRADE_HISTORY_CURRENCY
        "isReal" -> FidscriptAttrs.TRADE_HISTORY_ISREAL
        "contractId" -> FidscriptAttrs.TRADE_HISTORY_CONTRACTID
        "accountId" -> FidscriptAttrs.TRADE_HISTORY_ACCOUNTID
        "note" -> FidscriptAttrs.TRADE_HISTORY_NOTE
        "createdAt" -> FidscriptAttrs.TRADE_HISTORY_CREATEDAT
        else -> null
    }

    private fun decodeRows(body: String): List<Row> {
        val root = JSONObject(body)
        val result = root.optJSONArray("result") ?: return emptyList()
        val rows = mutableListOf<Row>()
        for (i in 0 until result.length()) {
            val joinRows = result.optJSONObject(i)
                ?.optJSONObject("data")
                ?.optJSONObject("datalog-result")
                ?.optJSONArray("join-rows")
                ?: continue
            for (j in 0 until joinRows.length()) {
                val row = joinRows.optJSONArray(j) ?: continue
                for (k in 0 until row.length()) {
                    val r = row.optJSONArray(k) ?: continue
                    if (r.length() < 3) continue
                    rows += Row(
                        entityId = r.optString(0),
                        attrId = r.optString(1),
                        value = if (r.isNull(2)) null else r.opt(2),
                        updatedAt = r.optLong(3)
                    )
                }
            }
        }
        return rows
    }

    private fun summarize(body: String): String = runCatching {
        val json = JSONObject(body)
        val message = json.optString("message").ifBlank { json.optString("error") }
        message.ifBlank { body.take(180) }
    }.getOrDefault(body.take(180))

    private companion object {
        const val OP_MERGE = "deep-merge-triple"
        const val OP_RETRACT = "retract-triple"
        const val OP_DELETE = "delete-entity"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** Raised when an operation needs a FIDScript session and none has been established. */
object NotSignedIn : IOException("No FIDScript session. Sign in with Deriv first.")