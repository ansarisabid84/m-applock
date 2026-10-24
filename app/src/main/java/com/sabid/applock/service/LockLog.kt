package com.sabid.applock.service

import android.util.Log
import com.sabid.applock.BuildConfig

/**
 * Diagnostics for the locking pipeline. `adb logcat -s AppLock` to watch it.
 *
 * This exists because the app previously logged nothing whatsoever, which made the one
 * failure that actually mattered — "I locked my gallery and it opened anyway" —
 * undiagnosable from a bug report. Every decision the lock path makes is now visible.
 *
 * Debug builds only, and that restriction is deliberate rather than tidiness. Logcat is
 * readable by adb and, on some ROMs, by other privileged software; a lock app writing
 * "the user just opened com.miui.gallery" into it would be leaking exactly the browsing
 * history it exists to protect. Nothing here is ever compiled into a release build, and
 * nothing here ever records a PIN, a hash, or the contents of a window.
 */
object LockLog {

    private const val TAG = "AppLock"

    fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    fun w(message: String) {
        if (BuildConfig.DEBUG) Log.w(TAG, message)
    }
}
