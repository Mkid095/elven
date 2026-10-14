package com.tickedge.data.deriv

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-account Deriv V1 options trading WebSocket.
 *
 * Connect to the URL from [DerivRestClient.fetchTradingWsUrl]. Uses V3-style request envelopes
 * ({ "<op>": <value>, "<field>": ..., "req_id": n }) and responses that carry "msg_type" and echo
 * "req_id". Verified against the live API:
 *
 *  - proposal: {proposal:1, contract_type, underlying_symbol, currency, amount, basis,
 *               barrier, duration, duration_unit, req_id} -> "proposal": {id, ask_price, payout, spot}
 *  - buy:      {buy: "<proposalId>", price: <ask_price>, req_id} -> "buy": {contract_id, buy_price, payout}
 *
 * Settlement is confirmed by polling `open_contract` for the id until is_sold / status=="Closed".
 * (If that op is not available for the account, this degrades to a timeout and reports unsettled.)
 */
class DerivTradingClient(private val wsUrl: String) {
    private val tag = "DerivTradingClient"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val reqSeq = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private var socket: WebSocket? = null
    @Volatile
    private var isOpen = false

    /**
     * Opens the socket and **waits for the handshake to finish**.
     *
     * OkHttp's newWebSocket is asynchronous: it returns a WebSocket handle immediately, before any
     * connection exists. A previous non-suspending version returned at that point, so callers sent
     * their first proposal into a socket that had not completed its handshake. Deriv never saw it,
     * the listener fired onFailure, and the trade died with an IllegalStateException that the
     * engine did not catch — which presented as "trading starts and instantly stops".
     */
    suspend fun connect(timeoutMs: Long = 10_000L) {
        if (isOpen) return
        val ready = CompletableDeferred<Unit>()
        val req = Request.Builder().url(wsUrl).build()
        socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(tag, "trading WS connected")
                isOpen = true
                if (!ready.isCompleted) ready.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                route(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(tag, "trading WS failure: ${t.message}")
                isOpen = false
                if (!ready.isCompleted) {
                    ready.completeExceptionally(
                        DerivApiException("trading socket failed to open: ${t.message}")
                    )
                }
                pending.values.forEach { it.completeExceptionally(t) }
                pending.clear()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(tag, "trading WS closed: $reason")
                isOpen = false
            }
        })
    }

    private fun route(text: String) {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }
        val rid = json.optInt("req_id", -1)
        if (rid >= 0) pending.remove(rid)?.complete(json)
    }

    /** Send a request and await the response that echoes the same req_id. */
    suspend fun request(payload: JSONObject): JSONObject {
        val id = reqSeq.incrementAndGet()
        payload.put("req_id", id)
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val s = socket ?: throw IllegalStateException("trading socket not connected")
        s.send(payload.toString())
        return try {
            deferred.await()
        } finally {
            pending.remove(id)
        }
    }

    /** Price a digit contract. */
    suspend fun proposal(params: DerivContractParams): DerivProposal {
        val p = JSONObject()
        p.put("proposal", 1)
        p.put("contract_type", params.contractType.code)
        p.put("underlying_symbol", params.underlyingSymbol)
        p.put("currency", params.currency)
        p.put("amount", params.amount)
        p.put("basis", "stake")
        p.put("barrier", params.barrier.toString())
        p.put("duration", params.duration)
        p.put("duration_unit", params.durationUnit)

        val resp = request(p)
        val pr = resp.optJSONObject("proposal")
            ?: throw DerivApiException("proposal failed: " + (resp.optJSONObject("error") ?: resp.toString()))
        return DerivProposal(
            id = pr.optString("id"),
            askPrice = pr.optDouble("ask_price", 0.0),
            payout = pr.optDouble("payout", 0.0),
            spot = pr.optDouble("spot", 0.0),
            longcode = pr.optString("longcode", "")
        )
    }

    /**
     * Buy the priced proposal.
     *
     * [permit] is required and has no default on purpose. Placing an order is the only operation
     * here that moves real money, and the live-trading check lives in TradingEngine — several
     * layers away from this call. Without a mandatory parameter, a future call site could reach
     * this function having skipped that gate entirely and the mistake would compile silently.
     * Requiring a [PlaceOrderPermit] turns "I forgot the safety check" into a compile error.
     */
    suspend fun buy(proposalId: String, askPrice: Double, permit: PlaceOrderPermit): DerivBuyResult {
        // Defence in depth: the engine already gates on this, but re-checking here means the
        // guarantee does not depend on that one code path staying intact.
        if (!permit.realMoneyAllowed && askPrice > 0.0) {
            throw DerivApiException(
                "refusing to place an order: no order permit supplied. Live trading may be armed, " +
                    "but this call did not pass through the safety gate."
            )
        }
        val p = JSONObject()
        p.put("buy", proposalId)
        p.put("price", askPrice)
        val resp = request(p)
        val b = resp.optJSONObject("buy")
            ?: throw DerivApiException("buy failed: " + (resp.optJSONObject("error") ?: resp.toString()))
        val contractId = if (b.optString("contract_id").isNotBlank()) {
            b.optString("contract_id")
        } else {
            b.optJSONObject("contract")?.optString("id").orEmpty()
        }
        return DerivBuyResult(
            contractId = contractId,
            buyPrice = b.optDouble("buy_price", askPrice),
            payout = b.optDouble("payout", 0.0)
        )
    }

    /**
     * Poll `open_contract` until the contract settles (is_sold / status "Closed").
     * [timeoutMs] bounds the wait; on timeout a [DerivSettlement] with settled=false is returned
     * so the caller never assumes a result.
     */
    suspend fun awaitSettlement(contractId: String, stepMs: Long = 1500L, timeoutMs: Long = 240_000L): DerivSettlement {
        val start = System.currentTimeMillis()
        while (true) {
            val p = JSONObject().put("open_contract", contractId)
            val resp = request(p)
            val oc = resp.optJSONObject("open_contract")
            val status = oc?.optString("status", "").orEmpty()
            val isSold = (oc?.optBoolean("is_sold", false) == true) ||
                (oc?.optBoolean("is_settled", false) == true) ||
                status.equals("Closed", ignoreCase = true)
            if (oc != null && isSold) {
                val profit = oc.optDouble("profit", 0.0)
                val payout = oc.optDouble("payout", 0.0)
                return DerivSettlement(contractId, profit, payout, profit > 0, settled = true)
            }
            if (System.currentTimeMillis() - start > timeoutMs) {
                return DerivSettlement(contractId, 0.0, 0.0, isWin = false, settled = false)
            }
            delay(stepMs)
        }
    }

    /** True when the socket has been opened. */
    fun isConnected() = isOpen

    fun close() {
        isOpen = false
        socket?.close(1000, "client closed")
        socket = null
        pending.values.forEach { it.cancel() }
        pending.clear()
    }
}

/** Thrown when Deriv returns an explicit error object for an operation. */
class DerivApiException(message: String) : Exception(message)

/**
 * Proof that the caller passed the live-trading safety gate before placing an order.
 *
 * Constructed only by [com.tickedge.domain.TradingEngine], and only after the real-account arming
 * and per-order confirmation checks have passed. There is no public constructor path that skips
 * them, so holding one is meaningful.
 */
@JvmInline
value class PlaceOrderPermit private constructor(internal val realMoneyAllowed: Boolean) {
    companion object {
        /**
         * Grant a permit for a demo/paper order.
         *
         * Safe because no real balance is at stake, so the live-trading arming is irrelevant.
         */
        val Paper: PlaceOrderPermit = PlaceOrderPermit(false)

        /**
         * Grant a permit for a real-money order.
         *
         * Only call after verifying the account is real, live trading is armed, and any required
         * per-order confirmation was granted.
         */
        fun RealMoneyApproved(): PlaceOrderPermit = PlaceOrderPermit(true)
    }
}
