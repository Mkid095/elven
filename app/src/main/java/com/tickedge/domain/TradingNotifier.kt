package com.tickedge.domain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.tickedge.MainActivity
import java.util.Locale

/**
 * Posts the notifications a trading app owes its user.
 *
 * Two kinds, deliberately separated:
 *
 *  - an **ongoing** notification that stays in the shade for as long as the bot is running, with
 *    an expanded breakdown of every strategy; and
 *  - **event** notifications for the moments that matter — the take-profit target reached, the
 *    stop loss hit, the daily limit reached.
 *
 * The split matters because they are read differently. The ongoing one is a status display: it is
 * there so that a glance at the lock screen says "this is running, this is what it is doing". The
 * event ones are alerts: they arrive at the moment something ended, and they must be seen.
 *
 * A bot spending real money has to say so on the lock screen. That is the whole reason this
 * class exists.
 */
class TradingNotifier(private val context: Context) {

    /**
     * Posts the ongoing notification for the strategies currently running.
     *
     * Called whenever any strategy's state changes, so the notification always reflects the truth
     * rather than the last thing that happened to be noticed.
     */
    fun postOngoing(states: Map<String, StrategyRunState>) {
        if (states.isEmpty()) {
            clearOngoing()
            return
        }
        val running = states.values.filter { it.isRunning }
        // Nothing is running: the notification would be a lie if it said the bot was trading, and
        // a stale one is worse than none.
        if (running.isEmpty()) {
            clearOngoing()
            return
        }

        ensureChannels()
        manager().notify(ONGOING_ID, buildOngoing(running, states.values))
    }

    /** Removes the ongoing notification. Called when the last strategy stops. */
    fun clearOngoing() {
        manager().cancel(ONGOING_ID)
    }

    /**
     * The notification shown for the moment between the service starting and the first state
     * arriving.
     *
     * It has to be a real notification: a foreground service must post one within a few seconds
     * of starting, or the system treats the service as having failed to start.
     */
    fun buildStarting(): Notification {
        // The channel has to exist before the notification is posted. On Android 8+ a
        // notification for a channel that does not exist is dropped silently — so the foreground
        // service would start with no visible notification at all, which is exactly the state the
        // service exists to prevent.
        ensureChannels()
        return NotificationCompat.Builder(context, CHANNEL_TRADING)
            .setContentTitle("TickEdge is trading")
            .setContentText("Starting…")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    /**
     * Posts an alert about something that ended a run.
     *
     * These are separate from the ongoing notification so they can be given their own channel and
     * their own importance: a user who does not want a running indicator can still be told that
     * their stop loss was hit.
     */
    fun postEvent(title: String, detail: String) {
        ensureChannels()
        val manager = manager()
        // A distinct id per event, so a second event does not replace the first. The user may want
        // to read the take-profit alert after the stop-loss one arrives.
        val id = EVENT_ID_BASE + (eventCounter++ % EVENT_ID_RANGE)
        manager.notify(
            id,
            NotificationCompat.Builder(context, CHANNEL_EVENTS)
                .setContentTitle(title)
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
        )
    }

    /**
     * The ongoing notification.
     *
     * Collapsed, it says what is happening right now. Expanded, it carries the full breakdown —
     * one block per strategy, with its trade count, its profit or loss, its record and what it is
     * doing — because a bot running two strategies needs two sets of numbers, and a single summary
     * line cannot carry them.
     *
     * Strategies that have stopped are listed after the running ones. A run that ended half an
     * hour ago is still part of "what is this bot doing", and hiding it would make the figures
     * look like they belong to the run still going.
     */
    private fun buildOngoing(
        running: List<StrategyRunState>,
        all: Collection<StrategyRunState>
    ): Notification {
        // The headline is the thing shown collapsed, when only one line is readable at a glance.
        // One strategy: say what it is doing. Several: say how many, and let the expanded view
        // carry the detail.
        val headline = when {
            running.size == 1 -> running.first().activeTradeSummary
                ?: "${running.first().strategyName} — ${running.first().status.label.lowercase()}"
            else -> "${running.joinToString(" and ") { it.strategyName }} — ${running.size} strategies running"
        }

        val title = "TickEdge is trading"

        // Running strategies first, then the ones that have stopped, each group in a stable order
        // so the expanded view does not reshuffle itself every time a figure changes.
        val ordered = running + all.filter { !it.isRunning }
            .sortedBy { it.strategyName }

        val lines = StringBuilder()
        ordered.forEachIndexed { index, state ->
            if (index > 0) lines.append("\n\n")
            lines.append(state.strategyName).append('\n')
            lines.append("  ").append(state.status.label)
            lines.append(" · ").append(state.tradesLabel)
            lines.append(" · ").append(state.pnlLabel)
            state.recordLabel?.let { lines.append(" · ").append(it) }
            if (state.consecutiveLosses > 0) {
                lines.append(" · ").append(state.consecutiveLosses)
                lines.append(if (state.consecutiveLosses == 1) " loss in a row" else " losses in a row")
            }
            state.activeTradeSummary?.let { lines.append("\n  ").append(it) }
        }

        return NotificationCompat.Builder(context, CHANNEL_TRADING)
            .setContentTitle(title)
            .setContentText(headline)
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.toString()))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun manager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = manager()

        // The running indicator. LOW, because it must not make a sound every time the bot places a
        // trade — it is a status display, not an alert.
        if (manager.getNotificationChannel(CHANNEL_TRADING) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_TRADING,
                    "Active trading",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shown for as long as the bot has a strategy running."
                    setShowBadge(false)
                }
            )
        }

        // The alerts. DEFAULT, so they can appear as a heads-up notification but do not take over
        // the screen the way an alarm would.
        if (manager.getNotificationChannel(CHANNEL_EVENTS) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_EVENTS,
                    "Trading alerts",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Take profit reached, stop loss hit, daily limit reached."
                }
            )
        }
    }

    companion object {
        const val CHANNEL_TRADING = "tickedge_trading"
        const val CHANNEL_EVENTS = "tickedge_events"
        const val ONGOING_ID = 4711

        /** Event ids start here and cycle, so each alert is its own notification. */
        private const val EVENT_ID_BASE = 5000
        private const val EVENT_ID_RANGE = 100
        private var eventCounter = 0

        /** Formats a signed money figure the way the notification shows it. */
        fun money(amount: Double): String =
            (if (amount >= 0) "+" else "-") + "$" +
                String.format(Locale.US, "%.2f", kotlin.math.abs(amount))
    }
}
