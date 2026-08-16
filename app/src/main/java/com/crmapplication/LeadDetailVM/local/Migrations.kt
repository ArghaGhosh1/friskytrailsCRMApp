package com.crmapplication.LeadDetailVM.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations for [CrmDatabase].
 *
 * Until v14 this database had **no** migrations at all — `AppModule` relied on
 * `fallbackToDestructiveMigration()`, so every version bump silently dropped every local table on the
 * next launch. That was tolerable while all local state was a cache of the server, but it is not:
 * `status_history` exists only on the device, notes and bug reports that failed to push exist only on
 * the device, and (since v14) so does a lead's stored call history.
 *
 * From here on, **a schema change needs a Migration in this file**. Adding an entity or a column
 * without one means shipping an update that destroys user data.
 */

/**
 * 13 → 14: adds the `calls` table backing locally-stored call history.
 *
 * Purely additive — no existing table is touched — so leads, notes, status history and bug reports all
 * survive the update. The SQL must match what Room generates for [CallEntity] exactly (column order,
 * nullability, `index_<table>_<column>` index names) or Room's identity check fails at open time with
 * "Room cannot verify the data integrity".
 *
 * `isFromServer` is INTEGER because SQLite has no boolean type; Room maps it to 0/1.
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `calls` (
                `id` TEXT NOT NULL,
                `leadId` TEXT,
                `phoneKey` TEXT NOT NULL,
                `number` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `dateMillis` INTEGER NOT NULL,
                `durationSeconds` INTEGER NOT NULL,
                `isFromServer` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_calls_leadId` ON `calls` (`leadId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_calls_phoneKey` ON `calls` (`phoneKey`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_calls_dateMillis` ON `calls` (`dateMillis`)")
    }
}

/**
 * 14 → 15: adds `calls.isVoicemail`, the agent's per-call "this reached a machine" mark.
 *
 * Additive with a DEFAULT, so existing stored calls survive and read as not-voicemail — the only
 * sensible reading, since no agent had the chance to mark them. NOT NULL DEFAULT 0 is required for the
 * schema to match [CallEntity]'s non-null Boolean.
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `calls` ADD COLUMN `isVoicemail` INTEGER NOT NULL DEFAULT 0")
    }
}

/** Every migration [CrmDatabase] knows about, in ascending order. */
val CRM_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_13_14, MIGRATION_14_15)

/**
 * Versions that may still be wiped rather than migrated, because no migration was ever written for
 * them — every build before v14 destroyed the database on upgrade anyway, so there is no user data
 * from those versions that a real migration could have preserved.
 *
 * Scoping the destructive fallback to these versions (instead of leaving it on for everything) is what
 * makes a future missing migration fail loudly at open time rather than quietly deleting the user's
 * leads, notes and call history.
 */
val DESTRUCTIVE_FALLBACK_VERSIONS: IntArray = (1..12).toList().toIntArray()
