package com.tickedge.domain

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The door an exact alarm knocks on.
 *
 * [TradeScheduler] points its [android.app.PendingIntent] at the [TradingService] directly, so the
 * foreground service is the one that actually runs the trade — this receiver's only job is the two
 * system-only cases the service cannot reach: [BOOT_COMPLETED] (re-arm every schedule after a
 * reboot) and [ACTION_SCHEDULE_SAVED] (arm the one schedule the user just saved). The trade work
 * itself never happens here.
 *
 * Kept as a receiver rather than folded into the service because a `BOOT_COMPLETED` broadcast is
 * something the system can deliver to a receiver at cold start when it will not start an arbitrary
 * service on the user's behalf.
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    /** Owns the work that outlives this short-lived receiver. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val application = context.applicationContext as android.app.Application
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> scope.launch {
                Log.i("ScheduleAlarmReceiver", "Reboot: re-arming all schedules")
                reArmAllOnBoot(application)
            }
            ACTION_SCHEDULES_CHANGED -> scope.launch {
                Log.i("ScheduleAlarmReceiver", "Schedule saved: arming all schedules")
                reArmAll(application)
            }
            else -> {
                // An alarm the scheduler armed reaches the TradingService, not this receiver.
                // Anything else is not ours.
            }
        }
    }

    /**
     * Re-arm after a reboot. The stable user key comes from the persisted Deriv session, the same
     * source the normal start path reads, so the schedules re-arm under the very identity they
     * were saved under.
     */
    private suspend fun reArmAllOnBoot(application: android.app.Application) {
        val runtime = TradingRuntime.get(application)
        val userId = runtime.oauth.currentUser()?.stableKey?.takeIf { it.isNotBlank() } ?: return
        TradeScheduler.armAll(application, userId)
    }

    /** Re-arm for the currently signed-in user, after any schedule edit. */
    private suspend fun reArmAll(application: android.app.Application) {
        val runtime = TradingRuntime.get(application)
        val userId = runtime.oauth.currentUser()?.stableKey?.takeIf { it.isNotBlank() } ?: return
        TradeScheduler.armAll(application, userId)
    }

    companion object {
        /** Sent by the app after it saves or pauses a schedule, to re-arm the alarms. */
        const val ACTION_SCHEDULES_CHANGED = "com.tickedge.action.SCHEDULES_CHANGED"
    }
}
