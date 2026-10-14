package com.tickedge.data.websocket

import android.util.Log
import com.tickedge.data.model.ConnectionStatus
import com.tickedge.data.model.MarketState
import com.tickedge.data.model.TickObservation
import com.tickedge.data.model.SupportedMarkets
import com.tickedge.domain.DigitAnalysisEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
 * Real-time market data for the 5 Deriv synthetic-index feeds over the V1 options public
 * WebSocket. No synthetic / offline data is generated: when the feed is down, markets are marked
 * STALE/OFFLINE instead of being fed fabricated ticks.
 *
 * Message schema is V3-style (confirmed against the live endpoint):
 *  - subscribe:  {"ticks_history":"<sym>","count":N,"end":"latest","style":"ticks","subscribe":1,"req_id":n}
 *  - history:    {"msg_type":"history","history":{"prices":[..],"times":[..]},"pip_size":p,"subscription":{"id":..}}
 *  - tick:       {"msg_type":"tick","tick":{"quote":..,"epoch":..,"symbol":..,"pip_size":..}}
 */
class DerivWebSocketService(private val scope: CoroutineScope) {
    private val tag = "DerivWebSocketService"

    private val publicWsUrl = "wss://api.derivws.com/trading/v1/options/ws/public"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    @Volatile
    private var isConnected = false
    @Volatile
    private var isConnecting = false
    @Volatile
    private var manualClosed = false
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0

    private val reqSeq = AtomicInteger(0)
    private val subscriptions = ConcurrentHashMap<String, String>() // symbol -> subscription.id

    /**
     * Full retained history per market, up to [MAX_TICKS].
     *
     * The state exposes only the *windowed* slice, but the full buffer must survive so that
     * raising the window size later can widen it again. Without this, [handleHistory] would trim
     * to the window at fetch time and the discarded ticks would be unrecoverable — selecting
     * "last 1000" after a default 30-tick start would silently yield only 30.
     */
    private val fullHistory = ConcurrentHashMap<String, List<TickObservation>>()

    private val _marketsState = MutableStateFlow<Map<String, MarketState>>(
        SupportedMarkets.ALL.associate { def ->
            def.symbol to MarketState(
                symbol = def.symbol,
                displayName = def.displayName,
                shortName = def.shortName,
                connectionStatus = ConnectionStatus.CONNECTING,
                pipSize = def.pipSize,
                configuredWindowSize = 30
            )
        }
    )
    val marketsState: StateFlow<Map<String, MarketState>> = _marketsState.asStateFlow()

    private val _overallConnection = MutableStateFlow(ConnectionStatus.CONNECTING)
    val overallConnection: StateFlow<ConnectionStatus> = _overallConnection.asStateFlow()

    private var currentWindowSize = 30

    /** Maximum ticks retained per market — also the history depth requested on subscribe. */
    private val MAX_TICKS = 1000

    fun start() {
        manualClosed = false
        connectWebSocket()
    }

    fun setWindowSize(size: Int) {
        val bounded = size.coerceIn(1, MAX_TICKS)
        currentWindowSize = bounded
        _marketsState.update { current ->
            current.mapValues { (symbol, state) ->
                // Re-slice from the retained full history so the window can grow as well as shrink.
                val source = fullHistory[symbol] ?: state.ticks
                val trimmed = source.takeLast(bounded)
                val freqs = DigitAnalysisEngine.computeFrequencies(trimmed, state.digitFrequencies)
                state.copy(
                    ticks = trimmed,
                    sampleSize = trimmed.size,
                    configuredWindowSize = bounded,
                    digitFrequencies = freqs
                )
            }
        }
    }

    private fun connectWebSocket() {
        if (isConnecting || isConnected || manualClosed) return
        isConnecting = true
        _overallConnection.value = ConnectionStatus.CONNECTING
        webSocket = client.newWebSocket(Request.Builder().url(publicWsUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(tag, "Public WS connected (V1)")
                isConnected = true
                isConnecting = false
                reconnectAttempt = 0
                _overallConnection.value = ConnectionStatus.LOADING_HISTORY
                releaseSubscriptions()
                SupportedMarkets.ALL.forEach { def -> subscribeMarket(webSocket, def.symbol) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleWebSocketMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(tag, "Public WS failure: ${t.message}")
                isConnected = false
                isConnecting = false
                markAll(ConnectionStatus.RECONNECTING)
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(tag, "Public WS closed: $reason")
                isConnected = false
                isConnecting = false
                if (!manualClosed) {
                    markAll(ConnectionStatus.OFFLINE)
                    scheduleReconnect()
                }
            }
        })
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.Default) {
            // `!isConnecting` matters: connectWebSocket() is async, so isConnected stays false
            // for the whole duration of an in-flight attempt. Without this guard the loop spins
            // through several backoff steps while a single attempt is still pending, and each
            // real failure burns several increments of reconnectAttempt. A transient DNS blip
            // then ramps straight to the 30s ceiling instead of retrying in 3s and recovering.
            while (isActive && !isConnected && !isConnecting && !manualClosed) {
                reconnectAttempt++
                val backoffMs = minOf(30_000L, 3_000L * reconnectAttempt)
                delay(backoffMs + (0..1_000).random())
                connectWebSocket()
            }
        }
    }

    private fun subscribeMarket(ws: WebSocket, symbol: String) {
        try {
            val req = JSONObject().apply {
                put("ticks_history", symbol)
                put("count", MAX_TICKS)
                put("end", "latest")
                put("style", "ticks")
                put("subscribe", 1)
                put("req_id", reqSeq.incrementAndGet())
            }
            ws.send(req.toString())
        } catch (e: Exception) {
            Log.e(tag, "Error subscribing to $symbol: ${e.message}")
        }
    }

    private fun releaseSubscriptions() {
        subscriptions.forEach { (_, subId) ->
            webSocket?.send(JSONObject().apply {
                put("forget", subId)
                put("forget_type", "ticks")
                put("req_id", reqSeq.incrementAndGet())
            }.toString())
        }
        subscriptions.clear()
    }

    private fun markAll(status: ConnectionStatus) {
        _overallConnection.value = status
        _marketsState.update { current ->
            current.mapValues { (_, state) ->
                if (state.connectionStatus == ConnectionStatus.LIVE) {
                    state.copy(connectionStatus = status)
                } else {
                    state
                }
            }
        }
    }

    private fun handleWebSocketMessage(jsonString: String) {
        val json = try {
            JSONObject(jsonString)
        } catch (e: Exception) {
            Log.e(tag, "Unparseable Deriv message: ${e.message}")
            return
        }
        val msgType = json.optString("msg_type")

        when (msgType) {
            "history" -> handleHistory(json)
            "tick" -> handleTick(json)
            // Previously every other msg_type fell through the when with no else, so Deriv's
            // `error` frames were discarded silently. A rejected or throttled subscription then
            // looked identical to a healthy one: history had already set the market LIVE, and
            // with no ticks ever arriving the displayed price simply froze, reading as "stale
            // or simulated data" with nothing to indicate a fault.
            "error" -> handleError(json)
            "ping" -> Unit // keepalive; nothing to do
            else -> Log.d(tag, "Unhandled Deriv msg_type=$msgType")
        }
    }

    /**
     * Surface a Deriv error frame instead of dropping it.
     *
     * Attributes the failure to a market when the error echoes the originating request, marks
     * that market OFFLINE so the UI stops presenting its last price as current, and leaves the
     * others alone. Only the numeric code and message are logged — never request payloads.
     */
    private fun handleError(json: JSONObject) {
        val code = json.optString("code")
        val message = json.optString("message")
        val symbol = json.optJSONObject("echo_req")?.optString("ticks_history").orEmpty()
        Log.w(tag, "Deriv error${if (symbol.isNotEmpty()) " for $symbol" else ""}: code=$code message=$message")

        if (symbol.isEmpty()) {
            // Connection-level failure: nothing to attribute it to, so surface it globally.
            markAll(ConnectionStatus.OFFLINE)
            return
        }
        _marketsState.update { current ->
            val state = current[symbol] ?: return@update current
            current + (symbol to state.copy(
                connectionStatus = ConnectionStatus.OFFLINE,
                lastError = "$code: $message"
            ))
        }
        refreshOverallConnection()
    }

    private fun handleHistory(json: JSONObject) {
        val echoReq = json.optJSONObject("echo_req") ?: return
        val symbol = echoReq.optString("ticks_history").ifEmpty { return }
        val history = json.optJSONObject("history") ?: return
        val prices = history.optJSONArray("prices")
        val times = history.optJSONArray("times")
        if (prices == null || times == null) return

        // Authoritative pip size from the response when present, else the market default.
        val pipSize = json.optInt("pip_size", 0).let { if (it > 0) it else SupportedMarkets.find(symbol).pipSize }
        json.optJSONObject("subscription")?.optString("id")?.let { subId -> subscriptions[symbol] = subId }

        val observations = mutableListOf<TickObservation>()
        val count = minOf(prices.length(), times.length())
        for (i in 0 until count) {
            val quote = prices.optDouble(i)
            val epoch = times.optLong(i)
            observations.add(DigitAnalysisEngine.createTickObservation(epoch, quote, pipSize))
        }

        // Retain the full fetched history; the state exposes only the windowed slice.
        val retained = observations.takeLast(MAX_TICKS)
        fullHistory[symbol] = retained

        _marketsState.update { current ->
            val oldState = current[symbol] ?: return@update current
            val bounded = retained.takeLast(currentWindowSize)
            val freqs = DigitAnalysisEngine.computeFrequencies(bounded, oldState.digitFrequencies)
            val lastObs = bounded.lastOrNull()
            val updated = oldState.copy(
                connectionStatus = ConnectionStatus.LIVE,
                lastQuote = lastObs?.quote,
                lastFormattedQuote = lastObs?.formattedQuote ?: "--",
                lastEpoch = lastObs?.epoch,
                lastDigit = lastObs?.lastDigit,
                pipSize = pipSize,
                ticks = bounded,
                sampleSize = bounded.size,
                digitFrequencies = freqs,
                lastUpdateTimestamp = System.currentTimeMillis(),
                lastError = null
            )
            current + (symbol to updated)
        }
        refreshOverallConnection()
    }

    private fun handleTick(json: JSONObject) {
        val tickObj = json.optJSONObject("tick") ?: return
        val symbol = tickObj.optString("symbol")
        val quote = tickObj.optDouble("quote")
        val epoch = tickObj.optLong("epoch")
        val pipSize = tickObj.optInt("pip_size", 0).let { if (it > 0) it else SupportedMarkets.find(symbol).pipSize }
        applyNewTick(symbol, DigitAnalysisEngine.createTickObservation(epoch, quote, pipSize))
    }

    /**
     * Ingests a new tick observation for a market, maintaining the rolling buffer and live
     * statistical frequencies.
     */
    fun applyNewTick(symbol: String, tick: TickObservation) {
        // Append to the retained history first, then re-slice the window from it. Appending
        // straight to state.ticks would lose the ticks outside the current window.
        val retained = ((fullHistory[symbol] ?: emptyList()) + tick).takeLast(MAX_TICKS)
        fullHistory[symbol] = retained

        _marketsState.update { current ->
            val oldState = current[symbol] ?: return@update current
            val newTicks = retained.takeLast(currentWindowSize)
            val freqs = DigitAnalysisEngine.computeFrequencies(newTicks, oldState.digitFrequencies)
            val priceDiff = tick.quote - (oldState.lastQuote ?: tick.quote)
            val updated = oldState.copy(
                connectionStatus = ConnectionStatus.LIVE,
                lastQuote = tick.quote,
                lastFormattedQuote = tick.formattedQuote,
                lastEpoch = tick.epoch,
                lastDigit = tick.lastDigit,
                lastPriceDiff = if (tick.priceDiff != 0.0) tick.priceDiff else priceDiff,
                pipSize = tick.pipSize,
                ticks = newTicks,
                sampleSize = newTicks.size,
                digitFrequencies = freqs,
                lastUpdateTimestamp = System.currentTimeMillis(),
                lastError = null
            )
            current + (symbol to updated)
        }
        refreshOverallConnection()
    }

    /**
     * Derive the aggregate connection badge from the per-market states.
     *
     * `_overallConnection` was previously only ever assigned CONNECTING / LOADING_HISTORY /
     * RECONNECTING / OFFLINE — nothing ever set LIVE, so the home header badge read "Syncing"
     * forever and the user could not tell whether data was actually streaming.
     */
    private fun refreshOverallConnection() {
        val all = _marketsState.value
        _overallConnection.value = when {
            all.isEmpty() -> ConnectionStatus.CONNECTING
            all.values.any {
                it.connectionStatus == ConnectionStatus.OFFLINE ||
                    it.connectionStatus == ConnectionStatus.RECONNECTING
            } -> ConnectionStatus.RECONNECTING
            all.values.all { it.connectionStatus == ConnectionStatus.LIVE } -> ConnectionStatus.LIVE
            else -> ConnectionStatus.LOADING_HISTORY
        }
    }

    fun disconnect() {
        manualClosed = true
        fullHistory.clear()
        reconnectJob?.cancel()
        reconnectJob = null
        releaseSubscriptions()
        webSocket?.close(1000, "client closed")
        webSocket = null
        isConnected = false
        isConnecting = false
        _overallConnection.value = ConnectionStatus.OFFLINE
    }
}
