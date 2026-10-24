package com.sabid.applock.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * The midnight-wrap and per-package cases below are the two that were actually broken
 * before [LockSchedule] existed, so they are the two worth pinning down permanently.
 */
class LockScheduleTest {

    private fun rule(
        start: Int,
        end: Int,
        days: Int = EVERY_DAY,
        locks: Boolean = true,
        packages: String = "",
        enabled: Boolean = true
    ) = TimeRule(
        id = 0,
        label = "t",
        startMinuteOfDay = start,
        endMinuteOfDay = end,
        daysMask = days,
        locksInside = locks,
        enabled = enabled,
        packagesCsv = packages
    )

    /** 2024-01-01 was a Monday, which keeps the day-bit arithmetic below readable. */
    private fun monday(hour: Int, minute: Int = 0) = LocalDateTime.of(2024, 1, 1, hour, minute)
    private fun tuesday(hour: Int, minute: Int = 0) = LocalDateTime.of(2024, 1, 2, hour, minute)
    private fun sunday(hour: Int, minute: Int = 0) = LocalDateTime.of(2024, 1, 7, hour, minute)

    @Test
    fun `same-day window is active inside and inactive outside`() {
        val r = rule(9 * 60, 15 * 60, days = MON)
        assertTrue(LockSchedule.isActive(r, monday(9, 0)))
        assertTrue(LockSchedule.isActive(r, monday(14, 59)))
        assertFalse(LockSchedule.isActive(r, monday(8, 59)))
        // The end minute is exclusive so two back-to-back windows cannot both fire.
        assertFalse(LockSchedule.isActive(r, monday(15, 0)))
    }

    @Test
    fun `window is scoped to its own days`() {
        val r = rule(9 * 60, 15 * 60, days = MON)
        assertTrue(LockSchedule.isActive(r, monday(10)))
        assertFalse(LockSchedule.isActive(r, tuesday(10)))
    }

    @Test
    fun `disabled rule never fires`() {
        assertFalse(LockSchedule.isActive(rule(0, 1440, enabled = false), monday(10)))
    }

    @Test
    fun `midnight-wrapping window covers the hours after midnight on the next day`() {
        // "Monday 23:00 to 02:00" must still be active at 01:00 on Tuesday. Checking only
        // the current day's bit made the whole second half of the window a no-op.
        val r = rule(23 * 60, 2 * 60, days = MON)
        assertTrue(LockSchedule.isActive(r, monday(23, 30)))
        assertTrue(LockSchedule.isActive(r, tuesday(1, 0)))
        assertFalse(LockSchedule.isActive(r, tuesday(2, 0)))
        assertFalse(LockSchedule.isActive(r, tuesday(22, 0)))
    }

    @Test
    fun `midnight wrap starting on sunday carries into monday`() {
        // Exercises the wrap in the yesterday-bit arithmetic itself: Sunday is bit 6, so
        // Monday's "yesterday" has to come out as 6 rather than -1.
        val r = rule(23 * 60, 2 * 60, days = SUN)
        assertTrue(LockSchedule.isActive(r, sunday(23, 30)))
        assertTrue(LockSchedule.isActive(r, monday(1, 0)))
        assertFalse(LockSchedule.isActive(r, monday(3, 0)))
    }

    @Test
    fun `no rules leaves the base set untouched`() {
        val base = setOf("com.a", "com.b")
        assertEquals(base, LockSchedule.resolve(base, emptyList(), monday(10)))
    }

    @Test
    fun `unlock window only releases the packages it names`() {
        // The regression this file exists for: an "allow YouTube" rule used to return an
        // empty set, unlocking the user's bank app along with it.
        val base = setOf("com.youtube", "com.bank")
        val rules = listOf(rule(16 * 60, 17 * 60, days = MON, locks = false, packages = "com.youtube"))
        assertEquals(setOf("com.bank"), LockSchedule.resolve(base, rules, monday(16, 30)))
    }

    @Test
    fun `unlock window with no packages releases everything in the base set`() {
        val base = setOf("com.youtube", "com.bank")
        val rules = listOf(rule(16 * 60, 17 * 60, days = MON, locks = false))
        assertEquals(emptySet<String>(), LockSchedule.resolve(base, rules, monday(16, 30)))
    }

    @Test
    fun `lock window adds packages that are not in the base set`() {
        val base = setOf("com.bank")
        val rules = listOf(rule(9 * 60, 15 * 60, days = MON, packages = "com.games"))
        assertEquals(setOf("com.bank", "com.games"), LockSchedule.resolve(base, rules, monday(10)))
    }

    @Test
    fun `lock beats unlock when both cover the same package`() {
        // Failing closed is the only safe tie-break for a lock app.
        val base = setOf("com.games")
        val rules = listOf(
            rule(9 * 60, 15 * 60, days = MON, locks = true, packages = "com.games"),
            rule(9 * 60, 15 * 60, days = MON, locks = false, packages = "com.games")
        )
        assertEquals(setOf("com.games"), LockSchedule.resolve(base, rules, monday(10)))
    }

    @Test
    fun `inactive rules are ignored entirely`() {
        val base = setOf("com.bank")
        val rules = listOf(rule(9 * 60, 15 * 60, days = MON, locks = false))
        assertEquals(base, LockSchedule.resolve(base, rules, monday(20)))
    }

    @Test
    fun `packages parses and trims csv`() {
        assertEquals(setOf("com.a", "com.b"), rule(0, 1, packages = " com.a , com.b , ").packages)
        assertEquals(emptySet<String>(), rule(0, 1, packages = "").packages)
        assertEquals(emptySet<String>(), rule(0, 1, packages = " , ").packages)
    }

    private companion object {
        const val MON = 1 shl 0
        const val SUN = 1 shl 6
        const val EVERY_DAY = 0b1111111
    }
}
