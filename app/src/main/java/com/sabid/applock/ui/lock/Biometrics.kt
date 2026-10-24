package com.sabid.applock.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.sabid.applock.core.util.Unlock

/**
 * Biometric unlock, and the phone's own screen lock as a separate, deliberately-chosen mode.
 *
 * Two entry points that must not be confused. [prompt] is the fingerprint shortcut *past* an
 * AppLock secret: the secret is still what protects the app, and biometrics are a faster way
 * to say the same thing. [promptDeviceCredential] is the case where the phone's PIN, pattern
 * or password *is* the whole unlock, because the user chose that over keeping a second secret.
 *
 * Keeping them apart is what makes each one honest. Folding DEVICE_CREDENTIAL into [prompt]
 * would mean anyone who could unlock the phone could open every protected app — which is a
 * legitimate thing to want, but only as an explicit choice made once with the trade-off in
 * front of you, never as a silent widening of a prompt the user thinks is guarding a separate
 * PIN. See [com.sabid.applock.core.data.AppSettings.deviceCredentialUnlock].
 *
 * [prompt] asks for BIOMETRIC_STRONG and falls back to WEAK only on hardware that has nothing
 * better, because refusing biometrics outright on those phones would just push users to a
 * shorter PIN. An earlier version computed a mask including DEVICE_CREDENTIAL and then never
 * passed it, so every unlock silently ran at WEAK — the tier a photograph can defeat.
 */
object Biometrics {

    /** The strongest class this device can actually authenticate with, or null for none. */
    private fun availableTier(activity: FragmentActivity): Int? {
        val manager = BiometricManager.from(activity)
        return listOf(Authenticators.BIOMETRIC_STRONG, Authenticators.BIOMETRIC_WEAK)
            .firstOrNull {
                runCatching { manager.canAuthenticate(it) }.getOrNull() ==
                    BiometricManager.BIOMETRIC_SUCCESS
            }
    }

    fun isAvailable(activity: FragmentActivity): Boolean = availableTier(activity) != null

    fun prompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onFallback: () -> Unit
    ) {
        val tier = availableTier(activity)
        if (tier == null) { onFallback(); return }

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                    onSuccess()

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    // User cancelled or hardware is busy — fall back to the manual secret
                    // instead of leaving them staring at a dead screen.
                    onFallback()
                }
            }
        )

        val info = runCatching {
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(tier)
                .setNegativeButtonText("Use PIN")
                .setConfirmationRequired(false)
                .build()
        }.getOrNull() ?: run { onFallback(); return }

        runCatching { prompt.authenticate(info) }.onFailure { onFallback() }
    }

    /**
     * Asks for the phone's own PIN, pattern or password.
     *
     * Whether there *is* a credential to ask for is [Unlock.deviceCredentialSet]'s question,
     * not this object's, and callers ask it there. BiometricManager cannot answer it:
     * `canAuthenticate(DEVICE_CREDENTIAL)` is not a supported query below API 30, and the
     * androidx.biometric docs point at KeyguardManager for exactly this.
     *
     * The mask is chosen by trying candidates in preference order and letting `build()` reject
     * the illegal ones, which is more robust than branching on `Build.VERSION`: the library's
     * rules for which combinations are legal are non-obvious (DEVICE_CREDENTIAL alone needs
     * API 30+; STRONG + DEVICE_CREDENTIAL is rejected on 28-29 specifically) and it enforces
     * them by throwing from `build()`, so probing asks the version of the library actually
     * present instead of the one that was current when this was written.
     *
     * [allowBiometric] is honoured where the platform allows it. Where it does not — below
     * API 30 there is no credential-only combination — a biometric class comes along whether
     * we like it or not, and that is the right compromise: the alternative is no prompt at all,
     * and the user's phone screen lock already accepts their fingerprint anyway.
     *
     * Negative-button text is deliberately never set. With DEVICE_CREDENTIAL in the mask the
     * library throws if it is, since the credential screen supplies its own cancel affordance.
     */
    fun promptDeviceCredential(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        allowBiometric: Boolean,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        val candidates = if (allowBiometric) {
            listOf(
                Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL,
                Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL,
                Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            listOf(
                Authenticators.DEVICE_CREDENTIAL,
                Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL
            )
        }

        val info = candidates.firstNotNullOfOrNull { mask ->
            runCatching {
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(mask)
                    .setConfirmationRequired(false)
                    .build()
            }.getOrNull()
        } ?: run { onFailure(); return }

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                    onSuccess()

                override fun onAuthenticationError(code: Int, message: CharSequence) = onFailure()
            }
        )

        runCatching { prompt.authenticate(info) }.onFailure { onFailure() }
    }
}
