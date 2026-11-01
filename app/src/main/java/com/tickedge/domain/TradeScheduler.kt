package com.tickedge.domain

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.tickedge.data.local.ScheduledRunEntity

/**
 * Arms the exact alarms that make a schedule fire.
 *
 * This is the glue between "the user saved a schedule" and "the phone actually wakes and trades at
 * that time". For every enabled schedule it computes the next start (and, where there is one, the
 * stop) via [ScheduleClock] and programs an [AlarmManager] exact alarm that targets
 * [ScheduleAlarmReceiver]. The receiver hands the job to the [TradingService] in the background,
 * which is what keeps the process alive while a scheduled run trades.
 *
 * A pure [Context] consumer rather than a ViewModel, for two reasons: it has to run from a
 * [android.content.BroadcastReceiver] (boot, schedule-saved) where no ViewModel exists, and it has
 * to be callable from anywhere the app saves a schedule. Keeping it in the domain next to
 * [HeadlessTrader] means the two never drift — the alarm that fires and the code it runs are one
 * pair.
 */
object TradeScheduler {

    /** Which alarm the service is being asked to act on. */
    const val ACTION_START = "start"
    const val ACTION_STOP = "stop"

    /** Distinct request codes per schedule, so re-arming one never cancels another's pending alarm. */
    private fun startCode(run: ScheduledRunEntity): Int = 0x0000 + run.id.toInt() and 0xffff
    private fun stopCode(run: ScheduledRunEntity): Int = 0x4000 + run.id.toInt() and 0xffff

    /**
     * Re-arm every enabled schedule for a user.
     *
     * Called when a schedule is saved, when the app returns to the foreground with schedules
     * present, and after a reboot. It is idempotent: the pending intents are cancelled and set
     * again, so calling it twice never leaves a doubled alarm.
     */
    suspend fun armAll(context: Context, userId: String) {
        val dao = TradingRuntime.get(context.applicationContext as Application).db.scheduledRunDao()
        val runs = dao.getEnabledForUser(userId)
        val now = System.currentTimeMillis()
        runs.forEach { run ->
            val start = ScheduleClock.nextFireTime(run, now)
            if (start != null) {
                scheduleStart(context, run, start)
            } else {
                cancelStop(context, run)
                // A one-shot with no future fire: nothing to arm, and no stop of its own.
            }
            // The stop alarm only has a moment to land at: recurring windows stop [durationMinutes]
            // after the start; a "once" window stops at its stored stopAt if set. Armed relative to
            // the same start that just fired, so it re-arms itself after every occurrence.
            val stop = start?.let { ScheduleClock.nextStopTime(run, it) }
            if (stop != null) scheduleStop(context, run, stop)
        }
    }

    /**
     * Cancel a single schedule's alarms — used when a schedule is paused or deleted.
     */
    fun cancel(context: Context, run: ScheduledRunEntity) {
        cancelStart(context, run)
        cancelStop(context, run)
    }

    private fun scheduleStart(context: Context, run: ScheduledRunEntity, at: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            at,
            servicePendingIntent(context, ACTION_START, run)
        )
        Log.i("TradeScheduler", "Armed start: schedule ${run.id} at $at")
    }

    private fun scheduleStop(context: Context, run: ScheduledRunEntity, at: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            at,
            servicePendingIntent(context, ACTION_STOP, run)
        )
    }

    private fun cancelStart(context: Context, run: ScheduledRunEntity) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(servicePendingIntent(context, ACTION_START, run))
    }

    private fun cancelStop(context: Context, run: ScheduledRunEntity) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(servicePendingIntent(context, ACTION_STOP, run))
    }

    /**
     * The [PendingIntent] that the alarm hands to the service.
     *
     * Distinct per (schedule, start-or-stop), so two schedules never cancel each other's alarms.
     * `getService` (not `getBroadcast`) because the intent targets the foreground [TradingService],
     * which is what stays up to trade; the receiver's only job is to forward to it.
     */
    private fun servicePendingIntent(context: Context, action: String, run: ScheduledRunEntity): PendingIntent {
        val intent = Intent(context, TradingService::class.java).apply {
            putExtra(TradingService.EXTRA_SCHEDULE_ACTION, action)
            putExtra(TradingService.EXTRA_SCHEDULE_ID, run.id)
            putExtra(TradingService.EXTRA_STRATEGY_ID, run.strategyId)
            putExtra(TradingService.EXTRA_ACCOUNT_KIND, run.accountKind)
        }
        val code = if (action == ACTION_START) startCode(run) else stopCode(run)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(context, code, intent, flags)
    }
}
