package com.sabid.applock.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.sabid.applock.core.model.VaultKind

// A `profiles` table and a LockedApp.profileId column used to live here. Nothing read
// either one — there was no profile UI and no query filtered on the column — so they were
// storage that could only ever go stale. Both are dropped in migration 1 -> 2.

@Entity(tableName = "locked_apps")
data class LockedApp(
    @PrimaryKey val packageName: String,
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * A window of time during which a set of packages is force-locked or force-unlocked.
 *
 * [daysMask] is a bitmask, bit 0 = Monday .. bit 6 = Sunday. [packagesCsv] is empty to
 * mean "every protected app", which is the common case and keeps the row small.
 */
@Entity(tableName = "time_rules")
data class TimeRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val daysMask: Int,
    /** true = apps are locked inside the window; false = apps are unlocked inside the window. */
    val locksInside: Boolean = true,
    val enabled: Boolean = true,
    /** Comma-separated package names, or empty for "all protected apps". */
    val packagesCsv: String = ""
) {
    val packages: Set<String>
        get() = packagesCsv.split(',').mapNotNull { it.trim().ifBlank { null } }.toSet()
}

@Entity(tableName = "intruder_logs")
data class IntruderLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val capturedAt: Long,
    @ColumnInfo(name = "photo_file") val photoFile: String?,
    val targetPackage: String,
    val failedAttempts: Int,
    /**
     * Exempts this one photo from the retention sweep.
     *
     * Per-photo rather than only global because the whole point of the retention default is
     * that a folder of stale selfies of the owner's own face is a liability, while the one
     * shot that actually caught someone is evidence the owner may need months later.
     */
    @ColumnInfo(name = "keep_forever", defaultValue = "0") val keepForever: Boolean = false
)

@Entity(tableName = "vault_items")
data class VaultItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val kind: VaultKind,
    /** File name inside filesDir/vault — content is AES-GCM encrypted. */
    val cipherFile: String,
    val thumbFile: String?,
    val sizeBytes: Long,
    val addedAt: Long = System.currentTimeMillis(),
    /** Original MIME so we can hand it back to a viewer on export. */
    val mimeType: String
)

/** A named set of protected apps the user can switch to in one tap (Work, Home, Kids ...). */
@Entity(tableName = "lock_profiles")
data class LockProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val packagesCsv: String
) {
    val packages: Set<String>
        get() = packagesCsv.split(',').mapNotNull { it.trim().ifBlank { null } }.toSet()
}

/** One line of the lock history: who got in, who was turned away. */
@Entity(tableName = "lock_events")
data class LockEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val packageName: String,
    /** One of [KIND_UNLOCK], [KIND_FAILED], [KIND_TIMED]. */
    val kind: String
) {
    companion object {
        const val KIND_UNLOCK = "UNLOCK"
        const val KIND_FAILED = "FAILED"
        const val KIND_TIMED = "TIMED"
    }
}
