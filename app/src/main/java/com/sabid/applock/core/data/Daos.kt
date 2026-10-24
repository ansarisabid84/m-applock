package com.sabid.applock.core.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LockedAppDao {
    @Query("SELECT * FROM locked_apps")
    fun observeAll(): Flow<List<LockedApp>>

    @Query("SELECT packageName FROM locked_apps")
    suspend fun allPackages(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(app: LockedApp)

    @Query("DELETE FROM locked_apps WHERE packageName = :pkg")
    suspend fun remove(pkg: String)

    @Query("DELETE FROM locked_apps")
    suspend fun clear()
}

@Dao
interface TimeRuleDao {
    @Query("SELECT * FROM time_rules ORDER BY startMinuteOfDay")
    fun observeAll(): Flow<List<TimeRule>>

    @Query("SELECT * FROM time_rules WHERE enabled = 1")
    suspend fun enabled(): List<TimeRule>

    @Upsert suspend fun upsert(rule: TimeRule): Long
    @Delete suspend fun delete(rule: TimeRule)
}

@Dao
interface IntruderDao {
    @Query("SELECT * FROM intruder_logs ORDER BY capturedAt DESC")
    fun observeAll(): Flow<List<IntruderLog>>

    @Query("SELECT * FROM intruder_logs")
    suspend fun all(): List<IntruderLog>

    /**
     * Rows the retention sweep may delete: older than [before] and not individually kept.
     *
     * Returns the rows rather than deleting them directly, because each one owns an encrypted
     * file on disk that has to be deleted too — a `DELETE` here would leave those files behind
     * forever with no row left to say what they were.
     */
    @Query("SELECT * FROM intruder_logs WHERE capturedAt < :before AND keep_forever = 0")
    suspend fun expiredBefore(before: Long): List<IntruderLog>

    @Query("UPDATE intruder_logs SET keep_forever = :keep WHERE id = :id")
    suspend fun setKeepForever(id: Long, keep: Boolean)

    /**
     * Repoints a row at a different file on disk.
     *
     * Exists for the one-time conversion of the encrypted `.enc` captures written by earlier
     * builds into plain JPEGs — see [com.sabid.applock.core.util.IntruderPhotos.migrateLegacy].
     */
    @Query("UPDATE intruder_logs SET photo_file = :name WHERE id = :id")
    suspend fun setPhotoFile(id: Long, name: String)

    @Insert suspend fun add(log: IntruderLog): Long

    @Query("DELETE FROM intruder_logs")
    suspend fun clear()

    @Delete suspend fun delete(log: IntruderLog)
}

@Dao
interface VaultDao {
    @Query("SELECT * FROM vault_items ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<VaultItem>>

    @Query("SELECT * FROM vault_items WHERE id = :id")
    suspend fun byId(id: Long): VaultItem?

    @Insert suspend fun add(item: VaultItem): Long
    @Delete suspend fun delete(item: VaultItem)
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM lock_profiles ORDER BY id")
    fun observeAll(): Flow<List<LockProfile>>

    @Upsert suspend fun upsert(profile: LockProfile): Long
    @Delete suspend fun delete(profile: LockProfile)
}

@Dao
interface LockEventDao {
    @Query("SELECT * FROM lock_events ORDER BY at DESC LIMIT 500")
    fun observeRecent(): Flow<List<LockEvent>>

    @Insert suspend fun add(event: LockEvent)

    @Query("DELETE FROM lock_events WHERE at < :before")
    suspend fun pruneBefore(before: Long)

    @Query("DELETE FROM lock_events")
    suspend fun clear()
}
