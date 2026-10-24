package com.sabid.applock.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verdict this class produces is the only thing standing between the user and a lock
 * app that quietly stopped locking, so both directions of every decision are pinned: it
 * must not claim protection is fine when it isn't, and it must not cry failure when
 * something is still covering the gap.
 */
class ProtectionStatusTest {

    /**
     * Defaults describe a healthy phone with one app locked and an AppLock secret set, so each
     * test names only the thing it is breaking. [lockedCount] defaults to 1 rather than 0
     * because a zero here switches off every unlock warning by design — see `enforcing`.
     */
    private fun status(
        protectionEnabled: Boolean = true,
        unlockUsable: Boolean = true,
        unlockChosen: Boolean = true,
        phoneScreenLockSet: Boolean = true,
        lockedCount: Int = 1,
        tamperGuardEnabled: Boolean = false,
        accessibilityGranted: Boolean = true,
        accessibilityRunning: Boolean = true,
        accessibilitySettled: Boolean = true,
        overlayGranted: Boolean = true,
        usageAccess: Boolean = true,
        guardRunning: Boolean = true,
        autostartPageAvailable: Boolean = false
    ) = ProtectionStatus(
        protectionEnabled = protectionEnabled,
        unlockUsable = unlockUsable,
        unlockChosen = unlockChosen,
        phoneScreenLockSet = phoneScreenLockSet,
        lockedCount = lockedCount,
        tamperGuardEnabled = tamperGuardEnabled,
        accessibilityGranted = accessibilityGranted,
        accessibilityRunning = accessibilityRunning,
        accessibilitySettled = accessibilitySettled,
        overlayGranted = overlayGranted,
        usageAccess = usageAccess,
        guardRunning = guardRunning,
        autostartPageAvailable = autostartPageAvailable
    )

    @Test
    fun `a fully granted device reports no problem`() {
        assertNull(status().problem)
        assertTrue(status().working)
    }

    @Test
    fun `a killed accessibility service still counts as protected when polling covers it`() {
        val s = status(accessibilityRunning = false, usageAccess = true, guardRunning = true)
        assertTrue("polling detection is real detection", s.working)
        assertFalse(s.broken)
        assertEquals("Your phone stopped fast detection", s.problem?.title)
    }

    @Test
    fun `a killed accessibility service with no fallback is a total failure`() {
        val s = status(accessibilityRunning = false, usageAccess = false)
        assertFalse(s.working)
        assertEquals("App detection was stopped", s.problem?.title)
    }

    @Test
    fun `nothing is declared stopped until the bind window has passed`() {
        val s = status(accessibilityRunning = false, accessibilitySettled = false)
        // Mid-bind on a cold start: unknown, not broken. Announcing a vendor kill here is
        // what made the warning show up on ordinary app launches.
        assertNull(s.problem)
    }

    @Test
    fun `the fix points at the vendor page when the device has one`() {
        val withPage = status(accessibilityRunning = false, autostartPageAvailable = true)
        assertEquals(SpecialGrant.AUTOSTART, withPage.problem?.fix)

        val withoutPage = status(accessibilityRunning = false, autostartPageAvailable = false)
        assertEquals(SpecialGrant.ACCESSIBILITY, withoutPage.problem?.fix)
    }

    @Test
    fun `no overlay grant outranks every detection problem`() {
        // A detector with nowhere to draw cannot ask for a PIN, so this is reported first
        // even when detection is also down.
        val s = status(overlayGranted = false, accessibilityRunning = false)
        assertFalse(s.working)
        assertEquals(SpecialGrant.OVERLAY, s.problem?.fix)
    }

    @Test
    fun `accessibility never granted is only a warning while polling works`() {
        val s = status(accessibilityGranted = false, accessibilityRunning = false)
        assertTrue(s.working)
        assertEquals("Running on backup detection", s.problem?.title)
    }

    @Test
    fun `accessibility never granted with no polling means nothing locks`() {
        val s = status(accessibilityGranted = false, accessibilityRunning = false, usageAccess = false)
        assertFalse(s.working)
        assertEquals("App detection is off", s.problem?.title)
    }

    @Test
    fun `switching protection off outranks everything`() {
        val s = status(protectionEnabled = false, overlayGranted = false)
        assertEquals("Protection is switched off", s.problem?.title)
        assertNull(s.problem?.fix)
    }

    @Test
    fun `a missing unlock is reported before any permission`() {
        val s = status(unlockUsable = false, unlockChosen = false, overlayGranted = false)
        assertEquals("No unlock is set yet", s.problem?.title)
        assertFalse(s.working)
    }

    @Test
    fun `the missing-unlock warning offers to fix itself in place`() {
        val problem = status(unlockUsable = false, unlockChosen = false).problem
        // No SpecialGrant, because the fix is not in system Settings — the banner turns this
        // into its "Set up now" button instead.
        assertNull(problem?.fix)
        assertTrue(problem!!.setUpUnlock)
    }

    @Test
    fun `a fresh install with nothing locked is not nagged about the unlock`() {
        // First run no longer forces a PIN, so this is the ordinary state of a brand-new
        // install. Warning here would be the first thing a new user ever saw, would be
        // unactionable, and is exactly the nag the setup change exists to remove.
        val s = status(unlockUsable = false, unlockChosen = false, lockedCount = 0)
        assertNull(s.problem)
        assertTrue(s.working)
    }

    @Test
    fun `the tamper guard alone is enough to want an unlock`() {
        val s = status(
            unlockUsable = false,
            unlockChosen = false,
            lockedCount = 0,
            tamperGuardEnabled = true
        )
        assertEquals("No unlock is set yet", s.problem?.title)
        assertFalse(s.working)
    }

    @Test
    fun `a removed phone screen lock is reported as its own problem`() {
        // Chosen but no longer usable: the user picked the phone's screen lock and then took
        // that lock off their phone. Distinct wording, because "no unlock is set yet" would
        // read as AppLock having lost their settings.
        val s = status(unlockUsable = false, unlockChosen = true, phoneScreenLockSet = false)
        assertEquals("Your phone's screen lock is gone", s.problem?.title)
        assertTrue(s.problem!!.setUpUnlock)
        assertFalse(s.working)
    }
}
