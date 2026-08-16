package com.crmapplication.LeadDetailVM.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        LeadEntity::class,
        NoteEntity::class,
        StatusHistoryEntity::class,
        BugReportEntity::class,
        CallEntity::class,
    ],

    // 13 → 14: added the `calls` table so a lead's call history is stored locally instead of being
    // re-read from the device log on every dialog open.
    //
    // Migrated, NOT wiped: `MIGRATION_13_14` (local/Migrations.kt) creates the `calls` table and
    // touches nothing else, so leads, notes, status history and bug reports all survive the update.
    // This was the first version to get a real migration — every earlier bump dropped the whole
    // database. Any future bump needs its own Migration in that file; see AppModule.provideDatabase.
    // 14 → 15: `calls.isVoicemail`, the agent's per-call voicemail mark (MIGRATION_14_15).
    version = 15,
    // Exported to app/schemas (see the ksp block in build.gradle.kts). Was false, which is part of how
    // the destructive-migration habit went unnoticed: with no exported schema there was nothing to
    // diff a migration against, and no way to tell that a version bump needed one.
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class CrmDatabase : RoomDatabase() {
    abstract fun leadDao(): LeadDao
    abstract fun noteDao(): NoteDao
    abstract fun statusHistoryDao(): StatusHistoryDao
    abstract fun bugReportDao(): BugReportDao
    abstract fun callDao(): CallDao
}
