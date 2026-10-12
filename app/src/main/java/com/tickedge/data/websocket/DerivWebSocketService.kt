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

    fun start() {
        manualClosed = false
        connectWebSocket()
    }

    fun setWindowSize(size: Int) {
        val bounded = size.coerceIn(1, 1000)
        currentWindowSize = bounded
        _marketsState.update { current ->
            current.mapValues { (_, state) ->
                val trimmed = state.ticks.takeLast(bounded)
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
            while (isActive && !isConnected && !manualClosed) {
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
                put("count", 1000)
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
        }
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

        _marketsState.update { current ->
            val oldState = current[symbol] ?: return@update current
            val bounded = observations.takeLast(currentWindowSize)
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
        _marketsState.update { current ->
            val oldState = current[symbol] ?: return@update current
            val newTicks = (oldState.ticks + tick).takeLast(currentWindowSize)
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
    }

    fun disconnect() {
        manualClosed = true
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
