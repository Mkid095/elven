package com.tickedge.domain

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the app alive and visible while the bot is trading.
 *
 * Without this, backgrounding the app means the OS may freeze it — the sockets stop receiving, no
 * settlement is detected, and a contract the user paid for is never recorded. Swiping the app away
 * from recents is worse: the Activity dies, the ViewModel with it, and the trading coroutine with
 * that.
 *
 * A foreground service is the system's own answer to both. The process is kept out of the killable
 * state, the user sees a persistent notification so the trading is never a surprise, and the
 * trading loop — which now lives in [TradingRuntime], outside the Activity — carries on.
 *
 * The notification is not decoration. A bot spending real money has to say so on the lock screen,
 * and the user has to be able to get back to it.
 *
 * This is a plain framework [Service] rather than `LifecycleService` on purpose: the lifecycle
 * artifact is not a dependency of this module, and a service that outlives the Activity has no
 * lifecycle to bind to anyway. Its own scope is cancelled in `onDestroy`.
 */
class TradingService : Service() {

    /**
     * Scope for this service's own work only.
     *
     * It is deliberately *not* the runtime's scope. Cancelling this must not cancel the trading
     * loop, which outlives the service — the service is stopped by the engine when the run ends,
     * not the other way round.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null

    /** Builds and posts the notification this service is responsible for. */
    private val notifier = TradingNotifier(this)

    override fun onCreate() {
        super.onCreate()
        startInForeground(notifier.buildStarting())

        // The notification is rebuilt from the runtime's published state whenever any strategy
        // changes. Watching the state rather than one engine's active trade is what lets the
        // notification describe every strategy at once — which strategies are running, how many
        // trades each has taken, and what each is up to.
        val runtime = TradingRuntime.peek()
        if (runtime == null) {
            // A null runtime here can only mean the system recreated this service after killing
            // the process under memory pressure (the engine and everything it watches live in
            // memory and died with it). Keep showing "TickEdge is trading" in that state would
            // claim the bot is running when it is not, so stop and clear the notification
            // instead of lying.
            notifier.clearOngoing()
            stopSelf()
            return
        }
        notificationJob = serviceScope.launch {
            runtime.runStates.collectLatest { states ->
                notifier.postOngoing(states)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.getStringExtra(EXTRA_SCHEDULE_ACTION)) {
            TradeScheduler.ACTION_START -> handleScheduledStart(intent)
            TradeScheduler.ACTION_STOP -> handleScheduledStop(intent)
        }
        if (intent?.action == ACTION_STOP) {
            stopTrading()
            return START_NOT_STICKY
        }
        // START_STICKY: if the system kills the process under memory pressure, the service is
        // restarted and the trading loop — which lives in the runtime, not in this process's
        // memory — picks up from where it was.
        return START_STICKY
    }

    /**
     * An alarm fired to start a scheduled run. This service is the thing that stays alive to
     * trade, so the headless start happens here: no Activity, no ViewModel, just the runtime and
     * the on-disk token and settings.
     */
    private fun handleScheduledStart(intent: Intent) {
        val strategyId = intent.getStringExtra(EXTRA_STRATEGY_ID) ?: return
        val accountKind = intent.getStringExtra(EXTRA_ACCOUNT_KIND)?.let {
            runCatching { com.tickedge.data.deriv.DerivAccountKind.valueOf(it) }.getOrNull()
        } ?: com.tickedge.data.deriv.DerivAccountKind.DEMO
        serviceScope.launch {
            val result = HeadlessTrader.start(this@TradingService, strategyId, accountKind)
            android.util.Log.i("TradingService", "Scheduled start ($strategyId): $result")
            // A schedule that cannot trade must say so, not fail quietly. An overnight run that
            // never started is the failure nobody is watching for, so it becomes a notification on
            // the events channel, not just a log line — the device stays the trader, but a person
            // still has to know the trade did not happen.
            when (result) {
                is HeadlessTrader.StartResult.TokenExpired -> {
                    android.util.Log.w("TradingService", "Scheduled start skipped: Deriv access token expired and cannot be refreshed")
                    notifier.postEvent(
                        "Scheduled trade did not start",
                        "The $accountKind run for \"$strategyId\" is waiting, but the Deriv access token has expired and cannot be refreshed. Sign in again to start it."
                    )
                }
                is HeadlessTrader.StartResult.SignedOut -> {
                    notifier.postEvent(
                        "Scheduled trade did not start",
                        "No trading account is signed in on this device, so the $accountKind run for \"$strategyId\" cannot start. Sign in to arm the schedule."
                    )
                }
                else -> Unit // Trading or AlreadyRunning: nothing to warn about.
            }
        }
    }

    /** An alarm fired to stop a scheduled window. */
    private fun handleScheduledStop(intent: Intent) {
        val strategyId = intent.getStringExtra(EXTRA_STRATEGY_ID) ?: return
        serviceScope.launch {
            HeadlessTrader.stop(this@TradingService, strategyId)
            android.util.Log.i("TradingService", "Scheduled stop: $strategyId")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Only this service's own coroutine goes. The engine keeps running in the runtime until the
        // user stops it or a risk limit is reached.
        serviceScope.cancel()
        // The running indicator goes with the service. Leaving it behind would tell the user the
        // bot is still trading when it is not.
        notifier.clearOngoing()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /** Ends the run and closes the session before the service goes away. */
    private fun stopTrading() {
        TradingRuntime.peek()?.stopAllEngines()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Brings the service to the foreground and updates its notification.
     *
     * The foreground-service type is passed explicitly because Android 14 rejects a
     * `startForeground` whose type does not match the one declared in the manifest.
     */
    private fun startInForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            TradingNotifier.ONGOING_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
    }

    companion object {
        const val ACTION_STOP = "com.tickedge.action.STOP_TRADING"

        // Extras the schedule alarm carries so the service knows which run it is starting or
        // stopping. Distinct from ACTION_STOP (the notification's stop) because that one stops
        // *every* engine, whereas a scheduled stop targets the one strategy in its window.
        const val EXTRA_SCHEDULE_ACTION = "schedule_action"
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_STRATEGY_ID = "strategy_id"
        const val EXTRA_ACCOUNT_KIND = "account_kind"

        /**
         * Starts the service, so the process is kept alive while the bot trades.
         *
         * Called when a run starts and stopped when it ends. Starting the service before the first
         * order matters: a service started later would leave a window where the OS could freeze
         * the app with a contract already open.
         */
        fun start(context: Context) {
            val intent = Intent(context, TradingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stops the service and removes the notification. */
        fun stop(context: Context) {
            context.stopService(Intent(context, TradingService::class.java))
        }
    }
}
