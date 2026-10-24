package com.sabid.applock.service

import com.sabid.applock.core.model.RelockPolicy
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * In-memory record of which protected packages the user has already unlocked
 * during this session, plus the failed-attempt state that drives lockout back-off.
 *
 * Deliberately never persisted: a reboot or a process death re-locks everything,
 * which is the behaviour a user expects from a lock app.
 *
 * Attempt state lives here rather than in the lock screen's composition because
 * [com.sabid.applock.ui.lock.LockActivity] finishes itself whenever the user navigates
 * away. Holding the counter in Compose state meant an attacker could reset the
 * back-off by pressing home and reopening the app, which defeated it entirely.
 */
object LockSession {

    private const val NO_EXPIRY = Long.MAX_VALUE

    /** Attempts before the first lockout kicks in. */
    const val LOCKOUT_AFTER = 5

    /** Cap on the back-off so the real owner is never permanently locked out. */
    private const val MAX_LOCKOUT_SECONDS = 300

    private val open = ConcurrentHashMap<String, Long>()

    /** Package currently in the foreground, as last reported by the accessibility service. */
    @Volatile
    var foregroundPackage: String? = null

    /** Package the lock screen is currently challenging for, if any. */
    @Volatile
    var challenging: String? = null

    /**
     * Failed attempts are counted globally, not per package. Counting per package would
     * let an attacker get [LOCKOUT_AFTER] free guesses per installed app.
     */
    @Volatile
    private var failedAttempts = 0

    @Volatile
    private var lockoutUntil = 0L

    // ---- grants -------------------------------------------------------------

    /** Deadlines for apps the user unlocked for a fixed time; these ignore relock policy. */
    private val timed = ConcurrentHashMap<String, Long>()

    /** Unlocks [pkg] for [minutes], regardless of leaving the app or turning the screen off. */
    fun grantFor(pkg: String, minutes: Int) {
        timed[pkg] = System.currentTimeMillis() + minutes * 60_000L
    }

    /** Milliseconds left on a timed unlock of [pkg], or 0 when there is none. */
    fun timedRemainingMs(pkg: String): Long {
        val left = (timed[pkg] ?: return 0L) - System.currentTimeMillis()
        if (left <= 0) { timed.remove(pkg); return 0L }
        return left
    }

    fun isOpen(pkg: String): Boolean {
        if (timedRemainingMs(pkg) > 0) return true
        val expiry = open[pkg] ?: return false
        if (expiry == NO_EXPIRY) return true
        if (System.currentTimeMillis() < expiry) return true
        open.remove(pkg)
        return false
    }

    /**
     * Records a successful unlock. Every policy starts as an open-ended grant; they
     * differ only in what clears it — IMMEDIATELY on [onLeftApp], SCREEN_OFF on
     * ACTION_SCREEN_OFF, TIMED via a deadline stamped on exit, RESTART on process death.
     */
    fun grant(pkg: String) {
        open[pkg] = NO_EXPIRY
        clearFailures()
    }

    /**
     * Called when the user navigates away from [pkg]. Under IMMEDIATELY we drop the
     * grant outright; under TIMED we start the brief-exit countdown from this moment,
     * which is what makes "switch to the camera and come back" feel seamless.
     */
    fun onLeftApp(pkg: String, policy: RelockPolicy, briefExitMinutes: Int) {
        if (!open.containsKey(pkg)) return
        when (policy) {
            RelockPolicy.IMMEDIATELY -> open.remove(pkg)
            RelockPolicy.TIMED -> {
                // Only stamp a deadline once. Re-stamping on every window event would
                // let time on the home screen extend the grant indefinitely.
                if (open[pkg] == NO_EXPIRY) {
                    open[pkg] = System.currentTimeMillis() + briefExitMinutes * 60_000L
                }
            }

            RelockPolicy.SCREEN_OFF, RelockPolicy.RESTART -> Unit
        }
    }

    fun revoke(pkg: String) {
        open.remove(pkg)
        timed.remove(pkg)
    }

    fun revokeAll() {
        open.clear()
        timed.clear()
    }

    /** Screen-off relock: drops ordinary grants but keeps the user's explicit timed unlocks. */
    fun revokeSession() {
        open.clear()
    }

    // ---- failed attempts / lockout -----------------------------------------

    val attempts: Int get() = failedAttempts

    /** Registers a wrong secret and returns the new total. */
    fun recordFailure(): Int {
        val next = failedAttempts + 1
        failedAttempts = next
        if (next >= LOCKOUT_AFTER) {
            // Clamp the exponent before shifting: 5 shl 31 overflows negative, which
            // would make min() pick a past deadline and switch the lockout off entirely.
            val steps = (next - LOCKOUT_AFTER).coerceIn(0, 16)
            val seconds = min(MAX_LOCKOUT_SECONDS, 5 shl steps)
            lockoutUntil = System.currentTimeMillis() + seconds * 1000L
        }
        return next
    }

    fun clearFailures() {
        failedAttempts = 0
        lockoutUntil = 0L
    }

    /** Milliseconds until entry is accepted again, or 0 when not locked out. */
    fun lockoutRemainingMs(): Long {
        val remaining = lockoutUntil - System.currentTimeMillis()
        return if (remaining > 0) remaining else 0L
    }

    val isLockedOut: Boolean get() = lockoutRemainingMs() > 0
}
