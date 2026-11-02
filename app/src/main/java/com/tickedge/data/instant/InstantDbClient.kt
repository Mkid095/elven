package com.tickedge.data.instant

import com.tickedge.data.local.SimulationSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.LinkedHashMap
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
        entityId: String
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

    /**
 * Create or update one row of `trade_history`.
 *
 * `userId` is written first in the same transaction, for the same reason as in
 * [upsertUserSettings]: a write that omits it fails the row permission check and the trade is
 * silently lost.
 */
    suspend fun upsertTrade(
        entityId: String,
        fields: Map<String, Any?>
    ): Result<TxnResult> {
        // Checked before building, because a caller that maps a name this class does not know
        // would otherwise get a transaction containing only the id triple — a row that exists but
        // holds none of the trade, which reads back as an empty trade rather than as an error.
        val resolved = fields.entries.mapNotNull { (name, value) ->
            if (value == null) return@mapNotNull null
            val attrId = tradeHistoryAttr(name)
                ?: return Result.failure(
                    IllegalArgumentException("Unknown trade_history attribute: $name")
                )
            attrId to value
        }
        if (resolved.isEmpty()) {
            return Result.failure(IllegalArgumentException("trade_history write had no fields"))
        }

        // userId first, for the same reason as upsertUserSettings: the row's permission check
        // depends on it, so a write that reaches the server without it is rejected outright.
        val steps = mutableListOf<JSONArray>()
        resolved.firstOrNull { it.first == FidscriptAttrs.TRADE_HISTORY_USERID }
            ?.let { steps += step(OP_MERGE, entityId, it.first, it.second) }
        resolved.filter { it.first != FidscriptAttrs.TRADE_HISTORY_USERID }
            .forEach { (attrId, value) -> steps += step(OP_MERGE, entityId, attrId, value) }
        steps += step(OP_MERGE, entityId, FidscriptAttrs.TRADE_HISTORY_ID, entityId)
        return transact(steps)
    }

    /**
     * Create or update the account record for a TickEdge person.
     *
     * Written at sign-up (first magic-code sign-in): the name the person gave is what identifies
     * them on the platform, and it belongs in the cloud so the next device can say who it is
     * without the person re-typing it.
     */
    suspend fun upsertTickedgeUser(
        fidUserId: String,
        entityId: String,
        fields: Map<String, Any?>
    ): Result<TxnResult> {
        val steps = mutableListOf(
            step(OP_MERGE, entityId, FidscriptAttrs.TICKEDGE_USERS_USERID, fidUserId),
            step(OP_MERGE, entityId, FidscriptAttrs.TICKEDGE_USERS_ID, entityId)
        )
        fields.forEach { (name, value) ->
            if (value == null) return@forEach
            val attrId = when (name) {
                "firstName" -> FidscriptAttrs.TICKEDGE_USERS_FIRSTNAME
                "lastName" -> FidscriptAttrs.TICKEDGE_USERS_LASTNAME
                "username" -> FidscriptAttrs.TICKEDGE_USERS_USERNAME
                "email" -> FidscriptAttrs.TICKEDGE_USERS_EMAIL
                "createdAt" -> FidscriptAttrs.TICKEDGE_USERS_CREATEDAT
                else -> return Result.failure(
                    IllegalArgumentException("Unknown tickedge_users attribute: $name")
                )
            }
            steps += step(OP_MERGE, entityId, attrId, value)
        }
        return transact(steps)
    }

    /** Delete an entity, e.g. to clear a user's synced history. */
    suspend fun deleteEntity(entityId: String): Result<TxnResult> =
        transact(listOf(JSONArray().put(OP_DELETE).put(entityId)))

    /**
     * Enumerate every entity id in a namespace that the current user can see.
     *
     * Rows are permission-scoped to `auth.id == data.userId`, so this returns only the signed-in
     * user's rows — the app can never enumerate (let alone delete) another user's record. A global
     * wipe of every user's cloud rows is an operator/admin step, not something this client can do.
     */
    suspend fun entityIdsIn(namespace: String): Result<List<String>> {
        val rows = query(namespace).getOrNull() ?: return Result.success(emptyList())
        return Result.success(rows.map { it.entityId }.distinct())
    }

    /**
     * Delete every row in a namespace the current user owns.
     *
     * Queries the namespace for its entity ids, then deletes each. This is how the "start from a
     * clean record" reset clears the cloud half of the history for the signed-in user; the local
     * Room wipe is separate and is what this pairs with.
     */
    suspend fun deleteAllIn(namespace: String): Result<Int> {
        val ids = entityIdsIn(namespace).getOrNull() ?: return Result.success(0)
        var deleted = 0
        for (id in ids) {
            if (deleteEntity(id).isSuccess) deleted++
        }
        return Result.success(deleted)
    }

    /** Remove a single attribute from an entity. */
    suspend fun retractTriple(entityId: String, attrId: String): Result<TxnResult> =
        transact(listOf(JSONArray().put(OP_RETRACT).put(entityId).put(attrId)))

    /**
     * Create or update one row of `sessions`.
     *
     * This is the live session a phone publishes so its siblings see the same record: written as
     * ACTIVE when it opens, re-written with running totals while it trades, and re-written as
     * COMPLETED with its final figures when it closes. Keyed on a deterministic [InstantDbClient.sessionEntityId],
     * so a session opened on one phone and pulled on another resolve to the same row.
     */
    suspend fun upsertSession(
        userId: String,
        entityId: String,
        fields: Map<String, Any?>
    ): Result<TxnResult> {
        val resolved = fields.entries.mapNotNull { (name, value) ->
            if (value == null) return@mapNotNull null
            val attrId = sessionAttr(name)
                ?: return Result.failure(IllegalArgumentException("Unknown sessions attribute: $name"))
            attrId to value
        }
        if (resolved.isEmpty()) return Result.failure(IllegalArgumentException("sessions write had no fields"))

        // userId first, for the same reason as the other upserts: the row's permission check
        // depends on it, so a write that reaches the server without it is rejected outright.
        val steps = mutableListOf<JSONArray>()
        resolved.firstOrNull { it.first == FidscriptAttrs.SESSIONS_USERID }
            ?.let { steps += step(OP_MERGE, entityId, it.first, it.second) }
        resolved.filter { it.first != FidscriptAttrs.SESSIONS_USERID }
            .forEach { (attrId, value) -> steps += step(OP_MERGE, entityId, attrId, value) }
        steps += step(OP_MERGE, entityId, FidscriptAttrs.SESSIONS_ID, entityId)
        return transact(steps)
    }

    /**
     * Tell every other signed-in device to stop trading, on the shared `run_control` channel.
     *
     * Each device's poll reads this row and, when [stopRequested] is set and newer than the one it
     * last saw, stops its own engines and clears the flag. That is the "stop on one phone, stop on
     * the other."
     */
    suspend fun requestRunStop(fidUserId: String): Result<TxnResult> {
        val entityId = controlEntityId(fidUserId)
        val now = System.currentTimeMillis()
        val steps = listOf(
            step(OP_MERGE, entityId, FidscriptAttrs.RUN_CONTROL_USERID, fidUserId),
            step(OP_MERGE, entityId, FidscriptAttrs.RUN_CONTROL_STOPREQUESTED, true),
            step(OP_MERGE, entityId, FidscriptAttrs.RUN_CONTROL_STOPREQUESTEDAT, now),
            step(OP_MERGE, entityId, FidscriptAttrs.RUN_CONTROL_UPDATEDAT, now),
            step(OP_MERGE, entityId, FidscriptAttrs.RUN_CONTROL_ID, entityId)
        )
        return transact(steps)
    }

    /** Clear the stop request, so a later one is the one that fires. */
    suspend fun clearRunStop(fidUserId: String): Result<TxnResult> {
        val entityId = controlEntityId(fidUserId)
        val now = System.currentTimeMillis()
        val steps = listOf(
            step(OP_MERGE, entityId, FidscriptAttrs.RUN_CONTROL_USERID, fidUserId),
            step(OP_MERGE, entityId, FidscriptAttrs.RUN_CONTROL_STOPREQUESTED, false),
            step(OP_MERGE, entityId, FidscriptAttrs.RUN_CONTROL_UPDATEDAT, now),
            step(OP_MERGE, entityId, FidscriptAttrs.RUN_CONTROL_ID, entityId)
        )
        return transact(steps)
    }

    /**
     * Read every row of [namespace] back as one map per entity: `{entityId: {attrName: value}}`.
     *
     * The query path returns a flat join-row stream; this groups it so a caller can ask "give me
     * the session with sessionId 7" without re-walking the wire shape. Attribute ids are resolved
     * back to names against the sessions / run_control namespaces (trade_history falls through to
     * ids).
     */
    suspend fun readNamespace(namespace: String): Result<Map<String, Map<String, Any?>>> =
        query(namespace).map { rows ->
            val byEntity = LinkedHashMap<String, LinkedHashMap<String, Any?>>()
            rows.forEach { row ->
                val name = attributeName(namespace, row.attrId, row.entityId)
                byEntity.getOrPut(row.entityId) { LinkedHashMap() }[name] = row.value
            }
            byEntity.mapValues { it.value }
        }

    /** Map a `sessions` field name to its attribute id. */
    private fun sessionAttr(name: String): String? = when (name) {
        "userId" -> FidscriptAttrs.SESSIONS_USERID
        "sessionId" -> FidscriptAttrs.SESSIONS_SESSIONID
        "strategyId" -> FidscriptAttrs.SESSIONS_STRATEGYID
        "strategy" -> FidscriptAttrs.SESSIONS_STRATEGY
        "isReal" -> FidscriptAttrs.SESSIONS_ISREAL
        "accountId" -> FidscriptAttrs.SESSIONS_ACCOUNTID
        "status" -> FidscriptAttrs.SESSIONS_STATUS
        "startedAt" -> FidscriptAttrs.SESSIONS_STARTEDAT
        "endedAt" -> FidscriptAttrs.SESSIONS_ENDEDAT
        "totalTrades" -> FidscriptAttrs.SESSIONS_TOTALTRADES
        "winCount" -> FidscriptAttrs.SESSIONS_WINCOUNT
        "lossCount" -> FidscriptAttrs.SESSIONS_LOSSCOUNT
        "netProfit" -> FidscriptAttrs.SESSIONS_NETPROFIT
        "grossProfit" -> FidscriptAttrs.SESSIONS_GROSSPROFIT
        "grossLoss" -> FidscriptAttrs.SESSIONS_GROSSLOSS
        "totalStaked" -> FidscriptAttrs.SESSIONS_TOTALSTAKED
        "initialSimulatedBalance" -> FidscriptAttrs.SESSIONS_INITIALSIMULATEDBALANCE
        "currentSimulatedBalance" -> FidscriptAttrs.SESSIONS_CURRENTSIMULATEDBALANCE
        "updatedAt" -> FidscriptAttrs.SESSIONS_UPDATEDAT
        else -> null
    }

    /**
     * Resolve an attribute id back to a readable name.
     *
     * Only the two namespaced families (sessions, run_control) are reverse-mapped; everything else
     * — trade_history, the `id` triple itself — falls back to the raw id, so a caller always gets
     * *something* it can key on rather than a lost row.
     */
    private fun attributeName(namespace: String, attrId: String, entityId: String): String =
        when (namespace) {
            FidscriptAttrs.NS_SESSIONS -> sessionToName[attrId] ?: attrId
            FidscriptAttrs.NS_RUN_CONTROL -> runControlToName[attrId] ?: attrId
            else -> attrId
        }

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
        // Added with the cross-device sync work: without these the values the Trade Settings
        // screen edits had nowhere to land, so a perfectly good push still dropped them.
        "thresholdPercent" -> FidscriptAttrs.USER_SETTINGS_THRESHOLDPERCENT
        "comparisonOperator" -> FidscriptAttrs.USER_SETTINGS_COMPARISONOPERATOR
        "analysisMetric" -> FidscriptAttrs.USER_SETTINGS_ANALYSISMETRIC
        "digitSelectionMode" -> FidscriptAttrs.USER_SETTINGS_DIGITSELECTIONMODE
        "windowSize" -> FidscriptAttrs.USER_SETTINGS_WINDOWSIZE
        "durationUnit" -> FidscriptAttrs.USER_SETTINGS_DURATIONUNIT
        "durationValue" -> FidscriptAttrs.USER_SETTINGS_DURATIONVALUE
        "martingaleEnabled" -> FidscriptAttrs.USER_SETTINGS_MARTINGALEENABLED
        "randomMarkets" -> FidscriptAttrs.USER_SETTINGS_RANDOMMARKETS
        "maxTradesPerMarket" -> FidscriptAttrs.USER_SETTINGS_MAXTRADESPERMARKET
        "stopTradingOnLimit" -> FidscriptAttrs.USER_SETTINGS_STOPTRADINGONLIMIT
        "minSampleSize" -> FidscriptAttrs.USER_SETTINGS_MINSAMPLESIZE
        "recoveryComparisonOperator" -> FidscriptAttrs.USER_SETTINGS_RECOVERYCOMPARISONOPERATOR
        "recoveryThresholdPercent" -> FidscriptAttrs.USER_SETTINGS_RECOVERYTHRESHOLD
        else -> null
    }

    /** Map a `trade_history` field name to its validated attribute id. */
    fun tradeHistoryAttr(name: String): String? = when (name) {
        "userId" -> FidscriptAttrs.TRADE_HISTORY_USERID
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
        "openedAt" -> FidscriptAttrs.TRADE_HISTORY_OPENEDAT
        "askPrice" -> FidscriptAttrs.TRADE_HISTORY_ASKPRICE
        "payout" -> FidscriptAttrs.TRADE_HISTORY_PAYOUT
        // Null is meaningful here and is dropped rather than written as 0: a trade with no recorded
        // ratio has to stay distinguishable from one that genuinely paid 0%.
        "payoutRatio" -> FidscriptAttrs.TRADE_HISTORY_PAYOUTRATIO
        "contractType" -> FidscriptAttrs.TRADE_HISTORY_CONTRACTTYPE
        "recoveryStep" -> FidscriptAttrs.TRADE_HISTORY_RECOVERYSTEP
        "outstandingBefore" -> FidscriptAttrs.TRADE_HISTORY_OUTSTANDINGBEFORE
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

    companion object {
        const val OP_MERGE = "deep-merge-triple"
        const val OP_RETRACT = "retract-triple"
        const val OP_DELETE = "delete-entity"
        val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * Stable FIDScript entity id for this user's settings row.
         *
         * Derived from the user id rather than stored locally, because the whole point of the
         * cloud copy is surviving a reinstall: after the app is wiped there is nothing left to read
         * a locally stored id from. Both sides compute the same value independently, so a push and a
         * pull always address the same row without a lookup. In the companion because it needs no
         * client, no session and no network — it is arithmetic over a string, and that is what
         * makes it testable without a device.
         */
        fun settingsEntityId(fidUserId: String): String =
            UUID.nameUUIDFromBytes("tickedge:settings:$fidUserId".toByteArray()).toString()

        /**
         * Stable entity id for one trade.
         *
         * Keyed on `contractId`, which Deriv guarantees unique per contract. Re-uploading therefore
         * overwrites the same row instead of duplicating the trade, which is what makes the upload
         * safe to retry after a failed or unknown-outcome transaction.
         */
        fun tradeEntityId(contractId: String): String =
            UUID.nameUUIDFromBytes("tickedge:trade:$contractId".toByteArray()).toString()

        /**
         * Stable FIDScript entity id for one trading session.
         *
         * Keyed on the session's own fields rather than its Room id alone. The Room id is a
         * per-database auto-increment, so two devices of the same person can both hold a "session
         * 1" — keying on the id alone would make their two distinct runs collide on one cloud row
         * and clobber each other. The fields below differ across devices: a session opens at its
         * own moment and may target a different strategy or account, so two genuinely different
         * sessions resolve to two rows, while re-publishing the *same* session (open, progress,
         * close) always hits the one row it started.
         */
        fun sessionEntityId(session: SimulationSessionEntity): String =
            UUID.nameUUIDFromBytes(
                "tickedge:session:${session.userId}:${session.startedAt}:${session.strategyId}:${session.accountId}:${session.id}".toByteArray()
            ).toString()

        /**
         * Stable FIDScript entity id for a person's run-control row.
         *
         * The shared stop flag is per-person, like the settings row: one row, written by any
         * device, read by all of them.
         */
        fun controlEntityId(fidUserId: String): String =
            UUID.nameUUIDFromBytes("tickedge:control:$fidUserId".toByteArray()).toString()

        /** Attribute id -> field name, for the `sessions` namespace. */
        val sessionToName: Map<String, String> = mapOf(
            FidscriptAttrs.SESSIONS_ID to "id",
            FidscriptAttrs.SESSIONS_USERID to "userId",
            FidscriptAttrs.SESSIONS_SESSIONID to "sessionId",
            FidscriptAttrs.SESSIONS_STRATEGYID to "strategyId",
            FidscriptAttrs.SESSIONS_STRATEGY to "strategy",
            FidscriptAttrs.SESSIONS_ISREAL to "isReal",
            FidscriptAttrs.SESSIONS_ACCOUNTID to "accountId",
            FidscriptAttrs.SESSIONS_STATUS to "status",
            FidscriptAttrs.SESSIONS_STARTEDAT to "startedAt",
            FidscriptAttrs.SESSIONS_ENDEDAT to "endedAt",
            FidscriptAttrs.SESSIONS_TOTALTRADES to "totalTrades",
            FidscriptAttrs.SESSIONS_WINCOUNT to "winCount",
            FidscriptAttrs.SESSIONS_LOSSCOUNT to "lossCount",
            FidscriptAttrs.SESSIONS_NETPROFIT to "netProfit",
            FidscriptAttrs.SESSIONS_GROSSPROFIT to "grossProfit",
            FidscriptAttrs.SESSIONS_GROSSLOSS to "grossLoss",
            FidscriptAttrs.SESSIONS_TOTALSTAKED to "totalStaked",
            FidscriptAttrs.SESSIONS_UPDATEDAT to "updatedAt"
        )

        /** Attribute id -> field name, for the `run_control` namespace. */
        val runControlToName: Map<String, String> = mapOf(
            FidscriptAttrs.RUN_CONTROL_ID to "id",
            FidscriptAttrs.RUN_CONTROL_USERID to "userId",
            FidscriptAttrs.RUN_CONTROL_STOPREQUESTED to "stopRequested",
            FidscriptAttrs.RUN_CONTROL_STOPREQUESTEDAT to "stopRequestedAt",
            FidscriptAttrs.RUN_CONTROL_UPDATEDAT to "updatedAt"
        )
    }
}

/** Raised when an operation needs a FIDScript session and none has been established. */
object NotSignedIn : IOException("No FIDScript session. Sign in with Deriv first.")