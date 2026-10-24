package com.sabid.applock.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sabid.applock.core.crypto.PinCrypto
import com.sabid.applock.core.model.IntruderViewMode
import com.sabid.applock.core.model.LockMethod
import com.sabid.applock.core.model.RelockPolicy
import com.sabid.applock.core.model.ThemeSkin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("applock_settings")

data class AppSettings(
    val onboarded: Boolean = false,
    val protectionEnabled: Boolean = true,
    val lockMethod: LockMethod = LockMethod.PIN,
    val pinLength: Int = 4,
    val secretSalt: String? = null,
    val secretHash: String? = null,

    /**
     * Authenticate with the phone's own screen lock instead of an AppLock PIN or pattern.
     *
     * Opt-in, off by default, and deliberately *exclusive* rather than additive: while this is
     * on, the phone's credential is the only thing the lock screen will accept, and any AppLock
     * secret left in [secretHash] is ignored rather than offered as a second door. Two accepted
     * answers would mean the weaker of the two decides how strong the lock is, and "which of my
     * two PINs does this want?" is a worse lock screen than either. [SettingsStore.setSecret]
     * clears this for the same reason — choosing a dedicated secret is choosing the other side.
     *
     * The trade-off is real and is stated at the point of choice: anyone who can unlock the
     * phone can then open every protected app. It is offered because the alternative for a user
     * who does not want a second PIN was no lock at all.
     */
    val deviceCredentialUnlock: Boolean = false,

    /**
     * The user was asked to set an unlock up and said "not now".
     *
     * Recorded only so the question is not asked again on the next switch they flip. It is not
     * what suppresses the warning banner — that is driven by whether anything is actually
     * being left unenforced. See [com.sabid.applock.core.util.ProtectionStatus].
     */
    val unlockSetupDeclined: Boolean = false,

    val recoveryQuestion: String? = null,
    val recoveryAnswerSalt: String? = null,
    val recoveryAnswerHash: String? = null,
    val biometricEnabled: Boolean = true,
    val randomKeyboard: Boolean = false,
    val invisiblePattern: Boolean = false,
    val vibrateOnKey: Boolean = true,
    val relockPolicy: RelockPolicy = RelockPolicy.SCREEN_OFF,
    val briefExitMinutes: Int = 1,
    val intruderEnabled: Boolean = true,

    /**
     * Wrong attempts before the front camera fires — and it fires on **every** attempt from
     * then on, not just this one. Two by default: one wrong entry is the owner fat-fingering
     * their own PIN, two is someone guessing.
     */
    val intruderAfterAttempts: Int = 2,

    /**
     * Whether captures are swept once they pass [intruderRetentionDays].
     *
     * On by default. An unbounded folder of selfies — mostly of the owner mistyping their own
     * PIN — is a growing liability rather than growing evidence, and it is the owner's face in
     * most of them. Individual photos can opt out via [IntruderLog.keepForever], so keeping
     * the one shot that matters never means keeping all of them.
     */
    val intruderAutoDelete: Boolean = true,

    /** How long a capture survives when [intruderAutoDelete] is on. */
    val intruderRetentionDays: Int = 30,

    /** Remembered layout of the intruder gallery, changed by the toggle or by pinching. */
    val intruderViewMode: IntruderViewMode = IntruderViewMode.GRID_LARGE,

    val theme: ThemeSkin = ThemeSkin.MIDNIGHT,
    val lockNewApps: Boolean = false,
    val powerSaver: Boolean = false,
    val panicWipeAfterAttempts: Int = 0,

    /**
     * Whether declining a challenge should also strip the app out of the recents list.
     *
     * On by default, because leaving it off makes the lock leak exactly what it was asked
     * to hide: the overview screen renders a live thumbnail of whatever the app had drawn
     * behind the lock screen, so a locked gallery still shows its photos to anyone who
     * swipes up. See [com.sabid.applock.service.TaskShield].
     */
    val hideFromRecents: Boolean = true,

    /**
     * Whether a locked app is closed again once the user leaves it.
     *
     * The other half of [hideFromRecents], and the half that costs something visible. Closing
     * an app at challenge time protects every app nobody got into, but an app the owner
     * unlocked and used is snapshotted with its contents the instant they press home — that
     * picture is what the overview screen draws, and no amount of killing the process afterwards
     * removes it. The only way to remove it is to empty the app's task, which means bringing
     * the app in front for a moment (behind an opaque cover) to press back at it. The user sees
     * the screen go black for a few hundred milliseconds.
     *
     * On by default: a lock that leaves a readable picture of the app in the switcher has
     * failed at the thing it is for. Off is a perfectly reasonable choice for someone who
     * would rather keep their place in the app. Ignored entirely when [hideFromRecents] is off.
     * See [com.sabid.applock.service.TaskShield.scrubOnLeave].
     */
    val closeLockedOnLeave: Boolean = true,

    /**
     * Shows a fake "App isn't responding" dialog in place of the lock screen. Long-pressing the
     * message reveals the real keypad. Meant to make a snooper give up rather than guess.
     */
    val fakeCrash: Boolean = false,

    /**
     * Whether Settings, the package installer and the vendor security app are challenged.
     *
     * This is what makes uninstall protection more than a speed bump: without it, anyone
     * holding the phone can walk into Settings, deactivate AppLock's device admin, and
     * uninstall it without ever meeting the PIN. See
     * [com.sabid.applock.core.util.UninstallGuard].
     */
    val guardSystemPages: Boolean = false,

    /**
     * Set the first time this device silently refuses a background activity start.
     *
     * Not a user setting — a learned fact about the ROM, persisted only so we do not have
     * to relearn it (at the cost of a second of the protected app being on screen) after
     * every reboot. See [com.sabid.applock.service.LockEngine].
     */
    val activityStartBlocked: Boolean = false
) {
    val secret: PinCrypto.Digest?
        get() = if (secretSalt != null && secretHash != null) PinCrypto.Digest(secretSalt, secretHash) else null
    val recoveryAnswer: PinCrypto.Digest?
        get() = if (recoveryAnswerSalt != null && recoveryAnswerHash != null)
            PinCrypto.Digest(recoveryAnswerSalt, recoveryAnswerHash) else null
}

class SettingsStore(private val context: Context) {

    private object K {
        val onboarded = booleanPreferencesKey("onboarded")
        val protection = booleanPreferencesKey("protection_enabled")
        val method = stringPreferencesKey("lock_method")
        val pinLength = intPreferencesKey("pin_length")
        val salt = stringPreferencesKey("secret_salt")
        val hash = stringPreferencesKey("secret_hash")
        val deviceCredential = booleanPreferencesKey("device_credential_unlock")
        val setupDeclined = booleanPreferencesKey("unlock_setup_declined")
        val recQ = stringPreferencesKey("recovery_q")
        val recSalt = stringPreferencesKey("recovery_salt")
        val recHash = stringPreferencesKey("recovery_hash")
        val biometric = booleanPreferencesKey("biometric")
        val randomKeyboard = booleanPreferencesKey("random_keyboard")
        val invisiblePattern = booleanPreferencesKey("invisible_pattern")
        val vibrate = booleanPreferencesKey("vibrate")
        val relock = stringPreferencesKey("relock_policy")
        val briefExit = intPreferencesKey("brief_exit_minutes")
        val intruder = booleanPreferencesKey("intruder")
        val intruderAfter = intPreferencesKey("intruder_after")
        val intruderAutoDelete = booleanPreferencesKey("intruder_auto_delete")
        val intruderRetentionDays = intPreferencesKey("intruder_retention_days")
        val intruderViewMode = stringPreferencesKey("intruder_view_mode")
        val theme = stringPreferencesKey("theme")
        val lockNew = booleanPreferencesKey("lock_new_apps")
        val powerSaver = booleanPreferencesKey("power_saver")
        val panicWipe = intPreferencesKey("panic_wipe")
        val hideFromRecents = booleanPreferencesKey("hide_from_recents")
        val closeLockedOnLeave = booleanPreferencesKey("close_locked_on_leave")
        val fakeCrash = booleanPreferencesKey("fake_crash")
        val guardSystemPages = booleanPreferencesKey("guard_system_pages")
        val activityStartBlocked = booleanPreferencesKey("activity_start_blocked")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            onboarded = p[K.onboarded] ?: false,
            protectionEnabled = p[K.protection] ?: true,
            lockMethod = p[K.method]?.let { runCatching { LockMethod.valueOf(it) }.getOrNull() } ?: LockMethod.PIN,
            pinLength = (p[K.pinLength] ?: 4).coerceIn(4, 8),
            secretSalt = p[K.salt],
            secretHash = p[K.hash],
            deviceCredentialUnlock = p[K.deviceCredential] ?: false,
            unlockSetupDeclined = p[K.setupDeclined] ?: false,
            recoveryQuestion = p[K.recQ],
            recoveryAnswerSalt = p[K.recSalt],
            recoveryAnswerHash = p[K.recHash],
            biometricEnabled = p[K.biometric] ?: true,
            randomKeyboard = p[K.randomKeyboard] ?: false,
            invisiblePattern = p[K.invisiblePattern] ?: false,
            vibrateOnKey = p[K.vibrate] ?: true,
            relockPolicy = p[K.relock]?.let { runCatching { RelockPolicy.valueOf(it) }.getOrNull() } ?: RelockPolicy.SCREEN_OFF,
            briefExitMinutes = p[K.briefExit] ?: 1,
            intruderEnabled = p[K.intruder] ?: true,
            intruderAfterAttempts = (p[K.intruderAfter] ?: 2).coerceIn(1, 10),
            intruderAutoDelete = p[K.intruderAutoDelete] ?: true,
            intruderRetentionDays = (p[K.intruderRetentionDays] ?: 30).coerceIn(1, 365),
            intruderViewMode = p[K.intruderViewMode]
                ?.let { runCatching { IntruderViewMode.valueOf(it) }.getOrNull() }
                ?: IntruderViewMode.GRID_LARGE,
            theme = p[K.theme]?.let { runCatching { ThemeSkin.valueOf(it) }.getOrNull() } ?: ThemeSkin.MIDNIGHT,
            lockNewApps = p[K.lockNew] ?: false,
            powerSaver = p[K.powerSaver] ?: false,
            panicWipeAfterAttempts = p[K.panicWipe] ?: 0,
            hideFromRecents = p[K.hideFromRecents] ?: true,
            closeLockedOnLeave = p[K.closeLockedOnLeave] ?: true,
            fakeCrash = p[K.fakeCrash] ?: false,
            guardSystemPages = p[K.guardSystemPages] ?: false,
            activityStartBlocked = p[K.activityStartBlocked] ?: false
        )
    }

    suspend fun current(): AppSettings = flow.first()

    /**
     * Stores a new unlock secret. [pinLength] is recorded alongside it so the keypad
     * knows how many dots to draw; a pattern ignores it.
     *
     * Switches [AppSettings.deviceCredentialUnlock] back off in the same transaction. Someone
     * who has just chosen a PIN of their own has chosen it *instead of* the phone's screen
     * lock; leaving that on would silently ignore the secret they were made to enter twice.
     * Clearing the declined flag is bookkeeping — there is nothing left to decline.
     */
    suspend fun setSecret(method: LockMethod, secret: CharArray, pinLength: Int? = null) {
        val digest = PinCrypto.hash(secret)
        context.dataStore.edit {
            it[K.method] = method.name
            it[K.salt] = digest.saltB64
            it[K.hash] = digest.hashB64
            it[K.deviceCredential] = false
            it[K.setupDeclined] = false
            if (pinLength != null) it[K.pinLength] = pinLength.coerceIn(4, 8)
        }
    }

    /**
     * Turns the phone's own screen lock on or off as AppLock's unlock.
     *
     * Callers must have just watched the user pass a device-credential prompt before enabling
     * this — see [com.sabid.applock.ui.lock.Biometrics.promptDeviceCredential]. That is not
     * only about proving who they are: it is the only way to find out that the prompt actually
     * works on this ROM before an unlock the user cannot answer is the only one configured.
     */
    suspend fun setDeviceCredentialUnlock(v: Boolean) = edit {
        it[K.deviceCredential] = v
        if (v) it[K.setupDeclined] = false
    }

    suspend fun setUnlockSetupDeclined(v: Boolean) = edit { it[K.setupDeclined] = v }

    suspend fun setRecovery(question: String, answer: CharArray) {
        val digest = PinCrypto.hash(answer)
        context.dataStore.edit {
            it[K.recQ] = question
            it[K.recSalt] = digest.saltB64
            it[K.recHash] = digest.hashB64
        }
    }

    suspend fun setOnboarded(v: Boolean) = edit { it[K.onboarded] = v }
    suspend fun setProtection(v: Boolean) = edit { it[K.protection] = v }
    suspend fun setBiometric(v: Boolean) = edit { it[K.biometric] = v }
    suspend fun setRandomKeyboard(v: Boolean) = edit { it[K.randomKeyboard] = v }
    suspend fun setInvisiblePattern(v: Boolean) = edit { it[K.invisiblePattern] = v }
    suspend fun setVibrate(v: Boolean) = edit { it[K.vibrate] = v }
    suspend fun setRelockPolicy(v: RelockPolicy) = edit { it[K.relock] = v.name }
    suspend fun setBriefExitMinutes(v: Int) = edit { it[K.briefExit] = v }
    suspend fun setIntruder(v: Boolean) = edit { it[K.intruder] = v }
    suspend fun setIntruderAfter(v: Int) = edit { it[K.intruderAfter] = v }
    suspend fun setIntruderAutoDelete(v: Boolean) = edit { it[K.intruderAutoDelete] = v }
    suspend fun setIntruderRetentionDays(v: Int) = edit { it[K.intruderRetentionDays] = v }
    suspend fun setIntruderViewMode(v: IntruderViewMode) = edit { it[K.intruderViewMode] = v.name }
    suspend fun setTheme(v: ThemeSkin) = edit { it[K.theme] = v.name }
    suspend fun setLockNewApps(v: Boolean) = edit { it[K.lockNew] = v }
    suspend fun setPowerSaver(v: Boolean) = edit { it[K.powerSaver] = v }
    suspend fun setPanicWipe(v: Int) = edit { it[K.panicWipe] = v }
    suspend fun setHideFromRecents(v: Boolean) = edit { it[K.hideFromRecents] = v }
    suspend fun setFakeCrash(v: Boolean) = edit { it[K.fakeCrash] = v }
    suspend fun setCloseLockedOnLeave(v: Boolean) = edit { it[K.closeLockedOnLeave] = v }
    suspend fun setGuardSystemPages(v: Boolean) = edit { it[K.guardSystemPages] = v }
    suspend fun setActivityStartBlocked(v: Boolean) = edit { it[K.activityStartBlocked] = v }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
