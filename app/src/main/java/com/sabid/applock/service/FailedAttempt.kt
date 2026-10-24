package com.sabid.applock.service

import android.content.Context
import com.sabid.applock.AppLockApp
import com.sabid.applock.core.util.VaultManager

/**
 * What happens when someone gets the PIN wrong.
 *
 * Lives here rather than in [com.sabid.applock.ui.lock.LockActivity] because the lock
 * screen now has two hosts — the activity and [LockOverlay] — and "intruder selfie after
 * three tries" silently not happening on whichever host the device actually used would be
 * a worse bug than the presentation bug that made two hosts necessary.
 */
object FailedAttempt {

    suspend fun handle(context: Context, targetPackage: String, attempts: Int) {
        LockHistory.record(com.sabid.applock.core.data.LockEvent.KIND_FAILED, targetPackage)
        val s = AppLockApp.get().settings.current()
        LockLog.d(
            "failed attempt $attempts on $targetPackage " +
                "(intruder=${s.intruderEnabled} after=${s.intruderAfterAttempts})"
        )

        // `>=`, not `==`. Equality took exactly one photo, at exactly the threshold attempt,
        // and then nothing however long someone kept guessing — so the one frame the owner
        // got was of whoever was there at attempt two, and the following fifteen tries went
        // unrecorded. Repeat captures are what make the log evidence rather than a snapshot.
        if (s.intruderEnabled && attempts >= s.intruderAfterAttempts) {
            IntruderCamera.capture(context, targetPackage, attempts)
        }

        if (s.panicWipeAfterAttempts in 1..attempts) {
            // Not a device wipe — we only clear this app's decrypted cache and session
            // grants. Destroying user data on a wrong PIN would be hostile.
            LockSession.revokeAll()
            VaultManager.purgeCache(context)
        }
    }
}
