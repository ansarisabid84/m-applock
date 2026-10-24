package com.sabid.applock.core.util

import android.app.KeyguardManager
import android.content.Context
import com.sabid.applock.core.data.AppSettings

/**
 * Whether AppLock has a way to prove it is the owner, and whether that way still works.
 *
 * Two questions that used to be the same one. "Is a secret hash stored?" answered both while
 * the only unlock was an AppLock PIN or pattern; now that the phone's own screen lock is an
 * option, they come apart:
 *
 *  - [chosen] is about the *user's* configuration, and is answered from settings alone. It is
 *    what decides whether to offer the setup prompt.
 *  - [usable] is about whether a challenge could actually be answered right now, and needs a
 *    Context, because in device-credential mode the answer depends on a fact that lives
 *    outside AppLock and can change without warning: whether the phone still has a screen
 *    lock at all.
 *
 * That gap is not hypothetical. Someone who picks "use my phone's screen lock" and later
 * removes their phone PIN has an AppLock configured with no answerable challenge. Treating
 * that as locked would brick every protected app *and* AppLock's own settings, with no way
 * back in. So it is [usable] that gates the challenge, and [ProtectionStatus] says loudly
 * what happened.
 *
 * Opening up rather than sealing shut is the safe direction here, which is not usually true and
 * is worth spelling out. Removing a phone's screen lock requires entering it first — Android
 * gives no way around that — so anyone able to put AppLock into this state could already have
 * answered the challenge it is now skipping. The fail-open costs nothing an attacker did not
 * already have, and buys the owner a way back in.
 */
object Unlock {

    /**
     * Whether the phone itself has a PIN, pattern or password set.
     *
     * `KeyguardManager.isDeviceSecure` and not `BiometricManager.canAuthenticate`, on the
     * androidx.biometric documentation's own advice: `DEVICE_CREDENTIAL` alone is not a
     * supported query below API 30, so `canAuthenticate` cannot answer this question on the
     * older half of our supported range. `isDeviceSecure` has meant exactly "a secure
     * credential is set" since API 23, and deliberately not `isKeyguardSecure`, which also
     * counts a swipe-to-dismiss keyguard as secure.
     */
    fun deviceCredentialSet(context: Context): Boolean = runCatching {
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    }.getOrDefault(false)

    /**
     * Whether the user has picked an unlock method. Does not promise it still works — see
     * [usable].
     */
    fun chosen(settings: AppSettings): Boolean =
        settings.secret != null || settings.deviceCredentialUnlock

    /** Whether a challenge presented right now could actually be answered. */
    fun usable(context: Context, settings: AppSettings): Boolean = when {
        settings.deviceCredentialUnlock -> deviceCredentialSet(context)
        else -> settings.secret != null
    }
}
