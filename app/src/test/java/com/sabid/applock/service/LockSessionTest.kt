package com.sabid.applock.service

import com.sabid.applock.core.model.RelockPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * LockSession is a singleton, so every test resets it first — otherwise the attempt
 * counter leaks between cases and the failures look random.
 */
class LockSessionTest {

    @Before
    fun reset() {
        LockSession.revokeAll()
        LockSession.clearFailures()
    }

    @Test
    fun `unknown package is not open`() {
        assertFalse(LockSession.isOpen("com.a"))
    }

    @Test
    fun `grant opens a package until revoked`() {
        LockSession.grant("com.a")
        assertTrue(LockSession.isOpen("com.a"))
        LockSession.revoke("com.a")
        assertFalse(LockSession.isOpen("com.a"))
    }

    @Test
    fun `grant does not leak to other packages`() {
        LockSession.grant("com.a")
        assertFalse(LockSession.isOpen("com.b"))
    }

    @Test
    fun `immediate policy drops the grant on exit`() {
        LockSession.grant("com.a")
        LockSession.onLeftApp("com.a", RelockPolicy.IMMEDIATELY, briefExitMinutes = 5)
        assertFalse(LockSession.isOpen("com.a"))
    }

    @Test
    fun `screen off policy keeps the grant across an exit`() {
        LockSession.grant("com.a")
        LockSession.onLeftApp("com.a", RelockPolicy.SCREEN_OFF, briefExitMinutes = 5)
        assertTrue(LockSession.isOpen("com.a"))
    }

    @Test
    fun `timed policy keeps the grant inside the window`() {
        LockSession.grant("com.a")
        LockSession.onLeftApp("com.a", RelockPolicy.TIMED, briefExitMinutes = 5)
        assertTrue(LockSession.isOpen("com.a"))
    }

    @Test
    fun `timed policy expires a zero-minute window`() {
        LockSession.grant("com.a")
        LockSession.onLeftApp("com.a", RelockPolicy.TIMED, briefExitMinutes = 0)
        assertFalse(LockSession.isOpen("com.a"))
    }

    @Test
    fun `timed deadline is stamped once so idling cannot extend it`() {
        // The bug this guards: re-stamping on every window event meant sitting on the
        // home screen renewed the brief-exit window forever.
        LockSession.grant("com.a")
        LockSession.onLeftApp("com.a", RelockPolicy.TIMED, briefExitMinutes = 0)
        LockSession.onLeftApp("com.a", RelockPolicy.TIMED, briefExitMinutes = 30)
        assertFalse(LockSession.isOpen("com.a"))
    }

    @Test
    fun `leaving an app that was never unlocked is a no-op`() {
        LockSession.onLeftApp("com.a", RelockPolicy.IMMEDIATELY, briefExitMinutes = 5)
        assertFalse(LockSession.isOpen("com.a"))
    }

    @Test
    fun `failures count up and are global`() {
        assertEquals(1, LockSession.recordFailure())
        assertEquals(2, LockSession.recordFailure())
        assertEquals(2, LockSession.attempts)
    }

    @Test
    fun `lockout starts only after the threshold`() {
        repeat(LockSession.LOCKOUT_AFTER - 1) { LockSession.recordFailure() }
        assertFalse(LockSession.isLockedOut)
        LockSession.recordFailure()
        assertTrue(LockSession.isLockedOut)
    }

    @Test
    fun `back-off never overflows into no lockout at all`() {
        // 5 shl 31 goes negative, which used to make the deadline land in the past and
        // silently disable the lockout for a determined attacker.
        repeat(60) { LockSession.recordFailure() }
        assertTrue(LockSession.isLockedOut)
        assertTrue(LockSession.lockoutRemainingMs() <= 300_000L)
    }

    @Test
    fun `clearing failures also clears the lockout`() {
        repeat(LockSession.LOCKOUT_AFTER + 3) { LockSession.recordFailure() }
        assertTrue(LockSession.isLockedOut)
        LockSession.clearFailures()
        assertEquals(0, LockSession.attempts)
        assertFalse(LockSession.isLockedOut)
        assertEquals(0L, LockSession.lockoutRemainingMs())
    }

    @Test
    fun `a successful grant clears the lockout`() {
        repeat(LockSession.LOCKOUT_AFTER + 1) { LockSession.recordFailure() }
        LockSession.grant("com.a")
        assertFalse(LockSession.isLockedOut)
    }
}
