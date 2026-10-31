package com.tickedge.data.deriv

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-account Deriv V1 options trading WebSocket.
 *
 * Connect to the URL from [DerivRestClient.fetchTradingWsUrl]. Uses V3-style request envelopes
 * ({ "<op>": <value>, "<field>": ..., "req_id": n }) and responses that carry "msg_type" and echo
 * "req_id". The whole flow below was verified end to end against a live demo account:
 *
 *  - subscribe: {transaction: 1, subscribe: 1} -> "subscription": {id}, then every settlement is
 *               *pushed* to the socket as {"msg_type":"transaction","transaction":{...}}
 *  - proposal:  {proposal:1, contract_type, underlying_symbol, currency, amount, basis,
 *                barrier, duration, duration_unit, req_id} -> "proposal": {id, ask_price, payout, spot}
 *  - buy:       {buy: "<proposalId>", price: <ask_price>, req_id} -> "buy": {contract_id, buy_price, payout}
 *
 * **Settlement arrives as a pushed `transaction` with `action: "sell"`**, carrying `contract_id`
 * and a net `amount` (payout on a win, 0 on a loss).
 *
 * This matters because the obvious call does not exist here: `open_contract` returns
 * `UnknownMethod: no recognized method in request` on this socket. An earlier version polled it
 * after every purchase, so each trade threw immediately after the money was committed — the order
 * was real, the app just never saw it finish, and the trading loop died. `portfolio` is supported
 * but useless here: a 1-tick contract is gone from it before the first 1-second poll lands.
 */
class DerivTradingClient(private val wsUrl: String) {
    private val tag = "DerivTradingClient"

    /**
     * Which account this socket is bound to, read from the OTP URL.
     *
     * The per-account OTP endpoint hands back `.../ws/demo?otp=…` or `.../ws/real?otp=…`, so the
     * kind is a property of the URL rather than something the caller has to remember to pass in.
     *
     * This matters because it is the only place that can actually tell whether an order is about to
     * touch real money. The safety gate in TradingEngine knows which account it *thinks* it selected;
     * this knows which account the socket is *actually* connected to. If those two ever disagree —
     * a stale trading pointer, a failed account switch — only this can catch it.
     */
    val accountKind: DerivAccountKind =
        if (Regex("/ws/real(?:[/?]|$)").containsMatchIn(wsUrl)) {
            DerivAccountKind.REAL
        } else {
            DerivAccountKind.DEMO
        }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val reqSeq = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()

    /**
     * Scope for fire-and-forget work owned by this client (currently the settlement subscription).
     *
     * Cancelled in [close] so a reconnect does not leave the previous client's coroutine running
     * against a dead socket.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Settlement events pushed by Deriv, keyed by contract id.
     *
     * Fed by the `transaction` subscription opened in [subscribeToSettlements]. Each entry is a
     * one-shot signal: once a contract has settled, its entry is consumed, so a late duplicate push
     * cannot resolve a second await on the same id.
     */
    private val settlements = ConcurrentHashMap<String, CompletableDeferred<SettlementSignal>>()

    /**
     * Settlements that arrived before anyone was waiting for them.
     *
     * A contract id only exists once `buy` returns, but a short contract can settle first. Parking
     * the unmatched push here means the race costs nothing; [awaitSettlement] drains it before it
     * ever blocks. Bounded because a contract nobody awaits (app closed, engine stopped) would
     * otherwise be retained for the life of the socket.
     */
    private val settledSignals = LinkedHashMap<String, SettlementSignal>()

    /**
     * Stake paid for each contract, recorded at buy time.
     *
     * The settlement push carries only what was credited back, so profit cannot be derived from it
     * alone — the stake has to be subtracted. A contract missing here settles with profit understated
     * by its stake rather than not at all.
     */
    private val stakes = ConcurrentHashMap<String, Double>()

    private var socket: WebSocket? = null
    @Volatile
    private var isOpen = false

    /**
     * Live account balance, pushed by Deriv on every change.
     *
     * `{"balance": 1, "subscribe": 1}` returns a subscription and then pushes a `balance` message
     * each time the figure moves — verified on a live account: a 0.35 buy pushed 11158.35 the
     * instant it was placed, and the settlement pushed 11158.72 when it paid out. So the number on
     * screen tracks the account instead of waiting for the next REST poll.
     *
     * Null until the first push arrives, which is distinct from 0.00: "not read yet" is a different
     * fact from "empty", and the UI must not show an empty account for an unread one.
     */
    private val _balance = MutableStateFlow<Double?>(null)
    val balance: StateFlow<Double?> = _balance.asStateFlow()

    /** A settled contract: net money moved, positive on a win and zero on a loss. */
    data class SettlementSignal(val contractId: String, val netAmount: Double, val balance: Double) {
        val isWin: Boolean get() = netAmount > 0.0
    }

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
                // Subscribing is fire-and-forget on purpose: it has to happen before any buy so no
                // settlement can land before there is anything listening for it, and its failure
                // must not block the connection.
                scope.launch { subscribeToSettlements() }
                scope.launch { subscribeToBalance() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                route(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(tag, "trading WS failure: ${t::class.java.simpleName}: ${t.message} (http=${response?.code})")
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

    /**
     * Opens the settlement stream.
     *
     * Deriv pushes a `transaction` message for every account transaction once this is subscribed:
     * `action: "buy"` when an order is placed and `action: "sell"` when it settles. Only the `sell`
     * carries the credited amount, so only that one resolves an await.
     */
    private suspend fun subscribeToSettlements() {
        try {
            val resp = request(JSONObject().put("transaction", 1).put("subscribe", 1))
            val subId = resp.optJSONObject("subscription")?.optString("id").orEmpty()
            if (subId.isBlank()) {
                Log.w(tag, "settlement subscription not granted: ${resp.optString("error")}")
            } else {
                Log.d(tag, "settlement subscription active: $subId")
            }
        } catch (e: Exception) {
            // Not fatal on its own — a trade can still be placed — but settlement will then time
            // out, so say so loudly rather than letting the first await look like a Deriv failure.
            Log.w(tag, "could not subscribe to settlements: ${e.message}")
        }
    }

    private fun route(text: String) {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }

        // Subscription pushes carry no req_id, so they are matched by content instead.
        when (json.optString("msg_type")) {
            "transaction" -> {
                routeSettlement(json.optJSONObject("transaction") ?: return)
                return
            }
            "balance" -> {
                json.optJSONObject("balance")?.let { onBalance(it.optDouble("balance", 0.0)) }
                return
            }
        }

        val rid = json.optInt("req_id", -1)
        if (rid >= 0) pending.remove(rid)?.complete(json)
    }

    /**
     * Opens the live balance stream.
     *
     * Separate from [subscribeToSettlements] because a failure here costs freshness, not
     * correctness: the account list still carries a balance from the REST fetch, it just stops
     * updating on its own.
     */
    private suspend fun subscribeToBalance() {
        try {
            val resp = request(JSONObject().put("balance", 1).put("subscribe", 1))
            val subId = resp.optJSONObject("subscription")?.optString("id").orEmpty()
            // The reply carries the current figure whether or not the subscription was granted, so
            // take it in both branches — otherwise the display waits for the next change to show
            // anything at all, and stays on a stale REST figure until money happens to move.
            resp.optJSONObject("balance")?.let { onBalance(it.optDouble("balance", 0.0)) }
            if (subId.isBlank()) {
                Log.w(tag, "balance subscription not granted: ${resp.optString("error")}")
            } else {
                Log.d(tag, "balance subscription active: $subId")
            }
        } catch (e: Exception) {
            Log.w(tag, "could not subscribe to balance updates: ${e.message}")
        }
    }

    private fun onBalance(value: Double) {
        Log.d(tag, "balance $value")
        _balance.value = value
    }

    private fun routeSettlement(tx: JSONObject) {
        if (!tx.optString("action").equals("sell", ignoreCase = true)) return
        val contractId = tx.optString("contract_id")
        if (contractId.isBlank()) return
        val signal = SettlementSignal(
            contractId = contractId,
            netAmount = tx.optDouble("amount", 0.0),
            balance = tx.optDouble("balance", 0.0)
        )
        Log.d(tag, "settled $contractId net=${signal.netAmount} balance=${signal.balance}")
        // Same ordering rule as the buy response: the transaction's own `balance` is not applied.
        // The balance push for this settlement carries the same figure and arrives in order.
        val waiter = settlements.remove(contractId)
        if (waiter != null) {
            waiter.complete(signal)
        } else {
            // Nobody is waiting yet — buffer it so awaitSettlement cannot miss it.
            synchronized(settledSignals) {
                settledSignals[contractId] = signal
                while (settledSignals.size > MAX_BUFFERED_SETTLEMENTS) {
                    settledSignals.remove(settledSignals.keys.first())
                }
            }
        }
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
        // Clamped for the same reason as `duration` below, and for the same reason it matters more here:
        // the venue refuses any amount carrying more than two decimals, so an unrounded figure fails
        // every order. Confirmed against the live endpoint — `amount: 0.525` is rejected with
        // `ContractBuyValidationError: Stake can not have more than 2 decimal places.`
        p.put("amount", toVenueAmount(params.amount))
        p.put("basis", "stake")
        // Digit Even/Odd carry no digit barrier — the class itself is the bet — so a barrier
        // would be meaningless and some venues reject the field on those types. Digits, Differs
        // and Over/Under all name a barrier and keep sending it.
        val hasBarrier = params.contractType != DerivContractType.DIGIT_EVEN &&
            params.contractType != DerivContractType.DIGIT_ODD
        if (hasBarrier) p.put("barrier", params.barrier.toString())
        // Clamped rather than rejected: a Trade Settings value above the API's ceiling would
        // otherwise fail every single order with no indication of why.
        p.put("duration", if (params.durationUnit == "t") params.duration.coerceIn(1, MAX_TICK_DURATION) else params.duration)
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
        //
        // The permit is matched against the kind of account THIS socket is bound to, not merely
        // checked for existence. The previous check was `!permit.realMoneyAllowed && askPrice > 0`,
        // which threw for every demo order — Paper was rejected by the very guard meant to protect
        // real money, so no demo trade could ever be placed. It also let the dangerous case
        // through: a Paper permit arriving on a real socket would have placed a real-money order,
        // because nothing here knew which account it was connected to.
        if (permit.kind != accountKind) {
            throw DerivApiException(
                "refusing to place an order: permit authorises ${permit.kind} but this socket is " +
                    "bound to $accountKind. The safety gate and the trading pointer disagree."
            )
        }
        val p = JSONObject()
        p.put("buy", proposalId)
        p.put("price", askPrice)
        val resp = request(p)
        val b = resp.optJSONObject("buy")
            ?: throw DerivApiException("buy failed: " + (resp.optJSONObject("error") ?: resp.toString()))

        // `balance_after` is deliberately NOT applied to the live balance here. It is tempting —
        // it would make the figure drop the moment the stake leaves — but it is not ordered against
        // the push stream. Verified across two back-to-back buys: the responses reported 11158.37
        // then 11158.02, and the pushes for those same events arrived *after* both responses. So
        // applying a response figure would move the display 11158.02 -> 11158.37 -> 11158.02,
        // visibly going backwards. The subscription push is a single ordered stream, so it is the
        // only thing allowed to move this number.

        val contractId = if (b.optString("contract_id").isNotBlank()) {
            b.optString("contract_id")
        } else {
            b.optJSONObject("contract")?.optString("id").orEmpty()
        }
        return DerivBuyResult(
            contractId = contractId,
            buyPrice = b.optDouble("buy_price", askPrice),
            payout = b.optDouble("payout", 0.0)
        ).also {
            stakes[contractId] = it.buyPrice
        }
    }

    /**
     * Awaits the pushed settlement for [contractId].
     *
     * The contract id only exists once [buy] returns, so a short-lived contract can settle — and
     * its push arrive — before the caller has a chance to wait. [routeSettlement] therefore parks
     * settlements nobody is waiting for in [settledSignals], and this consumes from there first;
     * the wait and the push are each done at most once, so neither ordering loses the result.
     *
     * On timeout a [DerivSettlement] with settled=false is returned rather than throwing, because
     * the stake is already committed and the caller needs a defined outcome to record, not a crash.
     */
    suspend fun awaitSettlement(
        contractId: String,
        timeoutMs: Long = SETTLEMENT_TIMEOUT_MS
    ): DerivSettlement {
        // 1. It already settled before we started waiting.
        synchronized(settledSignals) {
            settledSignals.remove(contractId)
        }?.let { return it.toDerivSettlement() }

        // 2. Otherwise register and wait. computeIfAbsent so a concurrent await on the same id cannot
        //    install a second waiter and strand the push in it.
        val waiter = settlements.computeIfAbsent(contractId) { CompletableDeferred() }

        // A push arriving between the drain above and this registration found no waiter and was
        // buffered instead, so re-check before blocking. Without this the contract would wait out
        // the full timeout for a result that had already been delivered.
        synchronized(settledSignals) {
            settledSignals.remove(contractId)
        }?.let {
            waiter.complete(it)
            settlements.remove(contractId)
            return it.toDerivSettlement()
        }

        val signal = withTimeoutOrNull(timeoutMs) { waiter.await() }
        settlements.remove(contractId)

        if (signal == null) {
            // 3. Nothing arrived. Distinguish "still running" from "closed, push missed" before
            //    giving up, so an unsettled trade is never silently recorded as a loss.
            confirmViaPortfolio(contractId)?.let { return it }
            Log.w(tag, "contract $contractId did not settle within ${timeoutMs}ms")
            return DerivSettlement(contractId, 0.0, 0.0, isWin = false, settled = false)
        }
        return signal.toDerivSettlement()
    }

    private fun SettlementSignal.toDerivSettlement(): DerivSettlement {
        // `amount` on the sell transaction is the money credited back at settlement, which is the
        // GROSS payout on a win and zero on a loss. Verified against a live account: the buy
        // response reported balance 11157.98, the settle credited 0.37, and the balance became
        // 11158.35 — so the 0.35 stake was debited separately at buy time and is NOT already
        // deducted from this figure. Profit therefore has to subtract the stake again.
        val stake = stakes.remove(contractId) ?: 0.0
        val payout = if (netAmount > 0.0) netAmount else 0.0
        return DerivSettlement(
            contractId = contractId,
            profit = payout - stake,
            payout = payout,
            isWin = isWin,
            settled = true
        )
    }

    /**
     * Distinguishes a contract that is still running from one that closed without its push arriving.
     *
     * `portfolio` lists contracts still in play. An absent contract means it has closed, which is
     * enough to call the trade settled — but not enough to claim a win, since this API exposes no
     * per-contract result here. So the honest answer is settled with no profit claimed. Under-
     * claiming a result is recoverable; inventing one is not.
     */
    private suspend fun confirmViaPortfolio(contractId: String): DerivSettlement? {
        return try {
            val resp = request(JSONObject().put("portfolio", 1))
            // org.json's JSONArray is a Java class with length()/get(i); it has no Kotlin `any`, so it is
            // walked by index.
            val contracts = resp.optJSONObject("portfolio")?.optJSONArray("contracts")
            val stillOpen = contracts != null &&
                (0 until contracts.length()).any { i ->
                    contracts.optJSONObject(i)?.optString("contract_id") == contractId
                }
            if (stillOpen) {
                null
            } else {
                Log.d(tag, "contract $contractId closed without a settlement push; " +
                    "reporting settled with no confirmed profit")
                DerivSettlement(contractId, 0.0, 0.0, isWin = false, settled = true)
            }
        } catch (e: Exception) {
            Log.w(tag, "portfolio check failed for $contractId: ${e.message}")
            null
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
        // Nobody is waiting on these once the socket is gone; failing them prevents a caller blocked
        // in awaitSettlement from hanging for the full timeout.
        settlements.values.forEach { it.completeExceptionally(DerivApiException("socket closed")) }
        settlements.clear()
        stakes.clear()
        synchronized(settledSignals) { settledSignals.clear() }
        scope.cancel()
    }
}

/** Thrown when Deriv returns an explicit error object for an operation. */
class DerivApiException(message: String) : Exception(message)

/**
 * How long to wait for a settlement push before treating the contract as unresolved.
 *
 * Ten ticks on Volatility 10 settles in a couple of seconds, so this is generous. It is a backstop
 * against a lost push, not the expected wait — a normal settlement returns from the push itself.
 */
private const val SETTLEMENT_TIMEOUT_MS = 60_000L

/**
 * Unmatched settlements retained while waiting for someone to ask for them.
 *
 * Only ever holds results for contracts nobody awaited, which in normal operation is zero or one.
 */
private const val MAX_BUFFERED_SETTLEMENTS = 16

/**
 * Longest tick duration this API accepts.
 *
 * Verified: `duration: 30, duration_unit: "t"` is rejected with
 * `ContractBuyValidationError: Number of ticks must be between 1 and 10.` Clamping here means a
 * larger value in Trade Settings shortens the contract instead of failing every order.
 */
internal const val MAX_TICK_DURATION = 10

/**
 * Renders a stake at the 2 decimal places the venue accepts.
 *
 * Sent as a string rather than a JSON number so the payload carries exactly two decimals. A double
 * like 0.525 would otherwise serialise as `0.525` and be refused; and even 0.35 can serialise as
 * `0.34999999999999998` depending on how it was computed, which would be refused for the same
 * reason. Non-finite or non-positive amounts collapse to a minimal stake rather than being sent
 * and rejected.
 */
private fun toVenueAmount(amount: Double): String {
    val safe = if (!amount.isFinite() || amount <= 0.0) MIN_STAKE else amount
    return String.format(Locale.US, "%.2f", safe)
}

/** Smallest stake the venue will price, used when a computed amount is unusable. */
private const val MIN_STAKE = 0.01

/**
 * Proof that the caller passed the live-trading safety gate before placing an order.
 *
 * Constructed only by [com.tickedge.domain.TradingEngine], and only after the real-account arming
 * and per-order confirmation checks have passed. There is no public constructor path that skips
 * them, so holding one is meaningful.
 *
 * The permit names the account kind it authorises rather than a bare boolean. That distinction is
 * load-bearing: `buy` compares this against the kind its own socket is bound to, so a demo permit
 * arriving at a real socket is refused. A boolean flag could not express that, and checking only
 * "was the gate passed" leaves the genuinely dangerous case — a stale pointer sending a paper
 * permit to the live account — completely unprotected.
 */
@JvmInline
value class PlaceOrderPermit private constructor(internal val kind: DerivAccountKind) {
    companion object {
        /**
         * Grant a permit for a demo/paper order.
         *
         * Safe because no real balance is at stake, so the live-trading arming is irrelevant. It
         * authorises *only* a demo socket; handing this to a real one is an error, not a shortcut.
         */
        val Paper: PlaceOrderPermit = PlaceOrderPermit(DerivAccountKind.DEMO)

        /**
         * Grant a permit for a real-money order.
         *
         * Only call after verifying the account is real, live trading is armed, and any required
         * per-order confirmation was granted.
         */
        fun RealMoneyApproved(): PlaceOrderPermit = PlaceOrderPermit(DerivAccountKind.REAL)
    }
}
