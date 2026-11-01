package com.tickedge.domain

import com.tickedge.data.local.ScheduledRunEntity
import java.util.Calendar
import java.util.TimeZone

/**
 * When does a schedule fire next?
 *
 * Pure math over a [ScheduledRunEntity] and "now", with no Android in it, so the answer a schedule
 * gives can be unit-tested without a device, a clock, or a permission. The alarm layer calls this
 * to know what to arm; the UI calls it to show "next run at 02:00" next to each schedule.
 *
 * Three shapes:
 *  - **once** — fire at [ScheduledRunEntity.startAt]; nothing after it has passed.
 *  - **daily** — fire at [ScheduledRunEntity.timeOfDay] ("HH:mm", the user's own timezone) each day.
 *  - **weekly** — fire at [ScheduledRunEntity.timeOfDay] on [ScheduledRunEntity.dayOfWeek] (1 = Sunday).
 *
 * "HH:mm" is parsed by hand rather than with `java.time`, because `LocalTime` needs API 26 and
 * this app's floor is 24.
 */
object ScheduleClock {

    /**
     * The next wall-clock moment [run] should start, strictly in the future, or null when there is
     * none (a one-shot that already fired, or a schedule that is not armed).
     */
    fun nextFireTime(run: ScheduledRunEntity, now: Long): Long? {
        if (!run.enabled) return null
        return when (run.recurring) {
            "daily" -> nextDaily(run.timeOfDay, now)
            "weekly" -> nextWeekly(run.dayOfWeek, run.timeOfDay, now)
            else -> if (run.startAt > now) run.startAt else null
        }
    }

    /**
     * The next stop time for a schedule that just started, or null when it should stop on its own
     * (a risk limit or the user pressing Stop). A "once" run with [ScheduledRunEntity.stopAt] set
     * stops there; recurring runs stop after their [ScheduledRunEntity.durationMinutes].
     */
    fun nextStopTime(run: ScheduledRunEntity, startedAt: Long): Long? = when (run.recurring) {
        "once" -> if (run.stopAt > 0L) run.stopAt else null
        else -> startedAt + run.durationMinutes.toLong() * 60_000L
    }

    /** Next daily fire at "HH:mm" in the device's local zone, strictly after [now]. */
    fun nextDaily(timeOfDay: String, now: Long): Long? {
        val parts = parseHm(timeOfDay) ?: return null
        val cal = localCalendar(now)
        cal.set(Calendar.HOUR_OF_DAY, parts.first)
        cal.set(Calendar.MINUTE, parts.second)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= now) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    /**
     * Next weekly fire on [dayOfWeek] (1 = Sunday .. 7 = Saturday) at [timeOfDay], strictly after
     * [now]. Advances a full week when this week's slot has already passed.
     */
    fun nextWeekly(dayOfWeek: Int, timeOfDay: String, now: Long): Long? {
        val parts = parseHm(timeOfDay) ?: return null
        val target = dayOfWeek.coerceIn(1, 7)
        val cal = localCalendar(now)
        cal.set(Calendar.HOUR_OF_DAY, parts.first)
        cal.set(Calendar.MINUTE, parts.second)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        var daysUntil = (target - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7
        if (daysUntil == 0 && cal.timeInMillis <= now) daysUntil = 7
        cal.add(Calendar.DAY_OF_YEAR, daysUntil)
        return cal.timeInMillis
    }

    private fun localCalendar(now: Long): Calendar =
        Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = now }

    /** "HH:mm" -> (hour, minute). Invalid or blank input is null, never an exception. */
    private fun parseHm(timeOfDay: String): Pair<Int, Int>? {
        val pieces = timeOfDay.split(':')
        if (pieces.size != 2) return null
        val hour = pieces[0].toIntOrNull() ?: return null
        val minute = pieces[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }
}
