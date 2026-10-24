package com.sabid.applock.core.data

import android.content.Context
import com.sabid.applock.core.model.ChallengeReason
import com.sabid.applock.core.model.LockDecision
import com.sabid.applock.core.util.UninstallGuard
import com.sabid.applock.core.util.Unlock
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime

/**
 * Single source of truth for "should this package be challenged right now?".
 *
 * Everything the lock engine needs is funnelled through here so the decision
 * logic lives in one testable place instead of inside the accessibility service.
 */
class LockRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsStore
) {
    val lockedApps: Flow<List<LockedApp>> = db.lockedApps().observeAll()
    val timeRules: Flow<List<TimeRule>> = db.timeRules().observeAll()
    val intruderLogs: Flow<List<IntruderLog>> = db.intruders().observeAll()
    val vaultItems: Flow<List<VaultItem>> = db.vault().observeAll()

    val profiles: Flow<List<LockProfile>> = db.profiles().observeAll()
    val lockEvents: Flow<List<LockEvent>> = db.lockEvents().observeRecent()

    suspend fun saveProfile(name: String, packages: Collection<String>) {
        db.profiles().upsert(LockProfile(name = name.trim(), packagesCsv = packages.joinToString(",")))
    }

    suspend fun deleteProfile(profile: LockProfile) = db.profiles().delete(profile)

    /** Makes [profile]'s apps the locked set, replacing whatever was locked before. */
    suspend fun applyProfile(profile: LockProfile) {
        val target = profile.packages
        val current = lockedPackages()
        (current - target).forEach { db.lockedApps().remove(it) }
        (target - current).forEach { db.lockedApps().add(LockedApp(it)) }
    }

    suspend fun recordEvent(pkg: String, kind: String) {
        db.lockEvents().add(LockEvent(at = System.currentTimeMillis(), packageName = pkg, kind = kind))
    }

    suspend fun pruneEvents(olderThanMs: Long) =
        db.lockEvents().pruneBefore(System.currentTimeMillis() - olderThanMs)

    suspend fun clearEvents() = db.lockEvents().clear()

    suspend fun lock(pkg: String) = db.lockedApps().add(LockedApp(pkg))
    suspend fun unlock(pkg: String) = db.lockedApps().remove(pkg)
    suspend fun lockedPackages(): Set<String> = db.lockedApps().allPackages().toSet()

    suspend fun lockAll(packages: Collection<String>) {
        packages.forEach { db.lockedApps().add(LockedApp(it)) }
    }

    suspend fun unlockAll() {
        db.lockedApps().clear()
    }

    suspend fun addIntruder(log: IntruderLog) = db.intruders().add(log)
    suspend fun allIntruders(): List<IntruderLog> = db.intruders().all()
    suspend fun expiredIntruders(before: Long): List<IntruderLog> =
        db.intruders().expiredBefore(before)

    suspend fun setIntruderKeepForever(id: Long, keep: Boolean) =
        db.intruders().setKeepForever(id, keep)

    suspend fun setIntruderPhotoFile(id: Long, name: String) =
        db.intruders().setPhotoFile(id, name)

    // Deliberately package-level plumbing only: both of these leave the encrypted photo on
    // disk. Callers go through com.sabid.applock.core.util.IntruderPhotos, which deletes the
    // file too — a row removed on its own leaves a photo the user thinks they deleted.
    suspend fun clearIntruders() = db.intruders().clear()
    suspend fun deleteIntruder(log: IntruderLog) = db.intruders().delete(log)

    suspend fun addVaultItem(item: VaultItem) = db.vault().add(item)
    suspend fun vaultItem(id: Long) = db.vault().byId(id)
    suspend fun deleteVaultItem(item: VaultItem) = db.vault().delete(item)

    suspend fun upsertRule(rule: TimeRule) = db.timeRules().upsert(rule)
    suspend fun deleteRule(rule: TimeRule) = db.timeRules().delete(rule)

    /** Base locked set with the user's time rules applied. See [LockSchedule]. */
    suspend fun effectiveLockedPackages(now: LocalDateTime = LocalDateTime.now()): Set<String> =
        LockSchedule.resolve(lockedPackages(), db.timeRules().enabled(), now)

    /**
     * The decision, and also the one place that refuses to present a lock screen nobody could
     * get past.
     *
     * The [Unlock.usable] check is what keeps that promise, and it has to be here rather than
     * in the lock screen. Since first-run no longer forces a PIN, a user can genuinely have
     * apps on their locked list with no unlock configured yet — and the honest response to that
     * is to let the app open and shout about it in the banner, not to throw up a keypad with
     * nothing to compare against. It also covers the case that arrives from outside AppLock: a
     * user of the phone-screen-lock option who later removes their phone PIN. Failing closed
     * there would leave every protected app, and AppLock's own settings, permanently
     * unreachable.
     */
    suspend fun decide(pkg: String, temporarilyOpen: Boolean): LockDecision {
        val s = settings.current()
        if (!s.protectionEnabled) return LockDecision.Allow
        if (!Unlock.usable(context, s)) return LockDecision.Allow
        if (temporarilyOpen) return LockDecision.Allow

        // Two independent reasons to challenge. The tamper guard is kept out of the user's
        // locked-app list on purpose — it is AppLock defending itself, not a choice the user
        // made, so it must not appear as a row they can accidentally switch off, and it must
        // not survive them turning the guard off either.
        if (pkg in effectiveLockedPackages()) {
            return LockDecision.Challenge(pkg, ChallengeReason.PROTECTED)
        }
        if (s.guardSystemPages && UninstallGuard.isGuardedSystemPage(pkg)) {
            return LockDecision.Challenge(pkg, ChallengeReason.TAMPER_GUARD)
        }
        return LockDecision.Allow
    }
}
