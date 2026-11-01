package com.tickedge.domain

import com.tickedge.data.local.ScheduledRunEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The next-fire / next-stop math for a schedule.
 *
 * These are the times an alarm is programmed with, so a wrong answer is a schedule that either
 * never trades or trades at the wrong moment — both silent and both expensive. Testing the pure
 * math without Android means a mistake here is caught before it ever reaches a real phone.
 */
class ScheduleClockTest {

    private val tz = TimeZone.getDefault()
    private val now = Calendar.getInstance(tz).apply {
        set(Calendar.HOUR_OF_DAY, 9)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun at(hour: Int, minute: Int, dayOffset: Int = 0): Long {
        val c = Calendar.getInstance(tz).apply {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, get(Calendar.DAY_OF_MONTH) + dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
    }

    @Test
    fun `a disabled schedule never fires`() {
        val run = ScheduledRunEntity(userId = "u", strategyId = "differs_with_recovery", enabled = false)
        assertNull(ScheduleClock.nextFireTime(run, now))
    }

    @Test
    fun `a one-shot in the future fires at its start`() {
        val run = ScheduledRunEntity(
            userId = "u", strategyId = "differs_with_recovery", recurring = "once",
            startAt = at(10, 0), stopAt = at(12, 0)
        )
        assertEquals(at(10, 0), ScheduleClock.nextFireTime(run, now))
    }

    @Test
    fun `a one-shot that already passed does not fire again`() {
        val run = ScheduledRunEntity(
            userId = "u", strategyId = "differs_with_recovery", recurring = "once",
            startAt = at(8, 0), stopAt = at(9, 0)
        )
        assertNull(ScheduleClock.nextFireTime(run, now))
    }

    @Test
    fun `a daily schedule not yet due today fires today`() {
        // 9:00 now, the window is at 10:00 — same day.
        val run = ScheduledRunEntity(
            userId = "u", strategyId = "differs_with_recovery", recurring = "daily",
            timeOfDay = "10:00", enabled = true
        )
        assertEquals(at(10, 0), ScheduleClock.nextFireTime(run, now))
    }

    @Test
    fun `a daily schedule already past today rolls to tomorrow`() {
        // 9:00 now, the window was at 8:00 — next occurrence is tomorrow.
        val run = ScheduledRunEntity(
            userId = "u", strategyId = "differs_with_recovery", recurring = "daily",
            timeOfDay = "08:00", enabled = true
        )
        assertEquals(at(8, 0, dayOffset = 1), ScheduleClock.nextFireTime(run, now))
    }

    @Test
    fun `a weekly schedule fires on its named weekday`() {
        // Pick this week's own day-of-week as the target: nextWeekly of "today at 22:00" must
        // resolve to either today (if 22:00 is still ahead of 9:00) or next week's same day.
        val today = Calendar.getInstance(tz).get(Calendar.DAY_OF_WEEK)
        val run = ScheduledRunEntity(
            userId = "u", strategyId = "even_odd", recurring = "weekly",
            dayOfWeek = today, timeOfDay = "22:00", enabled = true
        )
        val expected = ScheduleClock.nextWeekly(today, "22:00", now)
        assertEquals(expected, ScheduleClock.nextFireTime(run, now))
    }

    @Test
    fun `an invalid time-of-day returns null rather than throwing`() {
        val run = ScheduledRunEntity(
            userId = "u", strategyId = "differs_with_recovery", recurring = "daily",
            timeOfDay = "99:99", enabled = true
        )
        assertNull(ScheduleClock.nextFireTime(run, now))
    }

    @Test
    fun `a one-shot stop is its stored stopAt`() {
        val run = ScheduledRunEntity(
            userId = "u", strategyId = "differs_with_recovery", recurring = "once",
            startAt = at(10, 0), stopAt = at(12, 0)
        )
        assertEquals(at(12, 0), ScheduleClock.nextStopTime(run, at(10, 0)))
    }

    @Test
    fun `a one-shot with no stopAt never auto-stops`() {
        val run = ScheduledRunEntity(
            userId = "u", strategyId = "differs_with_recovery", recurring = "once",
            startAt = at(10, 0), stopAt = 0L
        )
        assertNull(ScheduleClock.nextStopTime(run, at(10, 0)))
    }

    @Test
    fun `a recurring window stops after its duration`() {
        val run = ScheduledRunEntity(
            userId = "u", strategyId = "differs_with_recovery", recurring = "daily",
            timeOfDay = "22:00", durationMinutes = 90, enabled = true
        )
        val started = at(22, 0)
        assertEquals(started + 90L * 60_000L, ScheduleClock.nextStopTime(run, started))
    }
}
