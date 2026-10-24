package com.sabid.applock.core.data

import java.time.LocalDateTime

/**
 * Pure resolution of "which packages are locked right now", given the user's base list
 * and their time rules.
 *
 * Deliberately free of Context, Room and coroutines so the rules — which are fiddly and
 * easy to get subtly wrong around midnight — can be unit tested directly.
 */
object LockSchedule {

    /** True when [now] falls inside the rule's day-and-time window. */
    fun isActive(rule: TimeRule, now: LocalDateTime): Boolean {
        if (!rule.enabled) return false

        val dayBit = 1 shl (now.dayOfWeek.value - 1) // Monday = bit 0
        val minuteOfDay = now.hour * 60 + now.minute

        return if (rule.startMinuteOfDay <= rule.endMinuteOfDay) {
            // A same-day window belongs to the day it starts on.
            rule.daysMask and dayBit != 0 &&
                minuteOfDay >= rule.startMinuteOfDay && minuteOfDay < rule.endMinuteOfDay
        } else {
            // The window wraps past midnight, so 23:00-02:00 on "Friday" must still be
            // active at 01:00 on Saturday. Checking only today's bit made the second half
            // of every wrapping window silently do nothing.
            val startedToday = rule.daysMask and dayBit != 0 && minuteOfDay >= rule.startMinuteOfDay
            val yesterdayBit = 1 shl ((now.dayOfWeek.value + 5) % 7) // value-1, wrapping
            val startedYesterday =
                rule.daysMask and yesterdayBit != 0 && minuteOfDay < rule.endMinuteOfDay
            startedToday || startedYesterday
        }
    }

    /**
     * Applies [rules] on top of [base].
     *
     * A rule names specific packages, or none at all to mean "everything in [base]".
     * Lock windows beat unlock windows when both cover the same package, because failing
     * closed is the only safe default for a lock app.
     *
     * The previous implementation ignored packages entirely: any active unlock window
     * returned an empty set, so a single "allow YouTube after school" rule unlocked the
     * user's banking app too.
     */
    fun resolve(
        base: Set<String>,
        rules: List<TimeRule>,
        now: LocalDateTime = LocalDateTime.now()
    ): Set<String> {
        if (rules.isEmpty()) return base

        val forceLock = mutableSetOf<String>()
        val forceUnlock = mutableSetOf<String>()

        for (rule in rules) {
            if (!isActive(rule, now)) continue
            val scope = rule.packages.ifEmpty { base }
            if (rule.locksInside) forceLock += scope else forceUnlock += scope
        }

        return (base + forceLock) - (forceUnlock - forceLock)
    }
}
