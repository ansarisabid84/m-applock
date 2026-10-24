package com.sabid.applock.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the guarded-package set.
 *
 * This is a smaller test than it looks. Two pieces of safety-critical behaviour elsewhere key
 * off this one predicate: the tamper guard challenges these packages, and — more importantly —
 * `LockEngine.contain` refuses to bounce them to the launcher when a lock screen cannot be
 * shown. If `com.android.settings` ever falls out of this set, that second rule silently stops
 * applying and a revoked overlay grant becomes unrecoverable, because every attempt to reach
 * Settings gets pushed back to home. Cheap to assert, expensive to discover on a user's phone.
 */
class UninstallGuardTest {

    @Test
    fun `guards the settings app`() {
        assertTrue(UninstallGuard.isGuardedSystemPage("com.android.settings"))
    }

    @Test
    fun `guards the stock and vendor package installers`() {
        assertTrue(UninstallGuard.isGuardedSystemPage("com.android.packageinstaller"))
        assertTrue(UninstallGuard.isGuardedSystemPage("com.google.android.packageinstaller"))
        assertTrue(UninstallGuard.isGuardedSystemPage("com.miui.packageinstaller"))
    }

    @Test
    fun `guards vendor security apps that can force-stop AppLock`() {
        assertTrue(UninstallGuard.isGuardedSystemPage("com.miui.securitycenter"))
        assertTrue(UninstallGuard.isGuardedSystemPage("com.coloros.safecenter"))
        assertTrue(UninstallGuard.isGuardedSystemPage("com.huawei.systemmanager"))
    }

    @Test
    fun `leaves ordinary apps alone`() {
        assertFalse(UninstallGuard.isGuardedSystemPage("com.miui.gallery"))
        assertFalse(UninstallGuard.isGuardedSystemPage("com.whatsapp"))
        assertFalse(UninstallGuard.isGuardedSystemPage("com.sabid.applock"))
    }

    @Test
    fun `does not guard the launcher or system ui`() {
        // Challenging either soft-bricks the device: the lock screen covers home, and
        // dismissing it returns to home, which raises it again. LockEngine exempts both, but
        // this set must not be pulling in the opposite direction.
        assertFalse(UninstallGuard.isGuardedSystemPage("com.android.systemui"))
        assertFalse(UninstallGuard.isGuardedSystemPage("com.miui.home"))
        assertFalse(UninstallGuard.isGuardedSystemPage("com.android.launcher3"))
    }

    @Test
    fun `matches on exact package name only`() {
        // A prefix match would drag in every com.android.settings.* helper package, several of
        // which are not user-facing pages at all.
        assertFalse(UninstallGuard.isGuardedSystemPage("com.android.settings.intelligence"))
        assertFalse(UninstallGuard.isGuardedSystemPage("settings"))
    }
}
