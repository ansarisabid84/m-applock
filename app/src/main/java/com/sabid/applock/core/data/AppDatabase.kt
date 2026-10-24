package com.sabid.applock.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sabid.applock.core.model.VaultKind

class Converters {
    @TypeConverter fun toKind(v: String): VaultKind = VaultKind.valueOf(v)
    @TypeConverter fun fromKind(v: VaultKind): String = v.name
}

@Database(
    entities = [
        LockedApp::class, TimeRule::class, IntruderLog::class, VaultItem::class,
        LockProfile::class, LockEvent::class
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lockedApps(): LockedAppDao
    abstract fun timeRules(): TimeRuleDao
    abstract fun intruders(): IntruderDao
    abstract fun vault(): VaultDao
    abstract fun profiles(): ProfileDao
    abstract fun lockEvents(): LockEventDao

    companion object {
        /**
         * Drops the unused profiles table and scopes time rules to packages.
         *
         * Written out by hand rather than left to fallbackToDestructiveMigration, which
         * would silently erase the user's protected-app list and — worse — orphan every
         * encrypted vault file on disk with no row left to decrypt it.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS locked_apps_new (" +
                        "packageName TEXT NOT NULL, addedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(packageName))"
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO locked_apps_new (packageName, addedAt) " +
                        "SELECT packageName, addedAt FROM locked_apps"
                )
                db.execSQL("DROP TABLE locked_apps")
                db.execSQL("ALTER TABLE locked_apps_new RENAME TO locked_apps")

                db.execSQL("DROP TABLE IF EXISTS profiles")
                db.execSQL("ALTER TABLE time_rules ADD COLUMN packagesCsv TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * Adds the per-photo retention opt-out.
         *
         * Existing rows default to 0, i.e. they are subject to the 30-day sweep. That is the
         * right default for photos captured before the feature existed — they were taken
         * under no promise of being kept — but it does mean the sweep can delete them, so the
         * column is added before the first sweep can ever run.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE intruder_logs ADD COLUMN keep_forever INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** Adds lock profiles and the lock history. Purely additive; nothing is rewritten. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS lock_profiles (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, packagesCsv TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS lock_events (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "at INTEGER NOT NULL, packageName TEXT NOT NULL, kind TEXT NOT NULL)"
                )
            }
        }

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "applock.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
        }
    }
}
