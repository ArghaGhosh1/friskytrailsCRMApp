package com.crmapplication.di

import android.content.Context
import androidx.room.Room
import com.crmapplication.LeadDetailVM.local.BugReportDao
import com.crmapplication.LeadDetailVM.local.CRM_MIGRATIONS
import com.crmapplication.LeadDetailVM.local.CallDao
import com.crmapplication.LeadDetailVM.local.CrmDatabase
import com.crmapplication.LeadDetailVM.local.DESTRUCTIVE_FALLBACK_VERSIONS
import com.crmapplication.LeadDetailVM.local.LeadDao
import com.crmapplication.LeadDetailVM.local.NoteDao
import com.crmapplication.LeadDetailVM.local.StatusHistoryDao
import com.crmapplication.LeadDetailVM.remote.ApiService
import com.crmapplication.LeadDetailVM.remote.FakeApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideApiService(): ApiService = FakeApiService()

    /**
     * Real migrations, so updating the app no longer destroys local data.
     *
     * This used to be a bare `fallbackToDestructiveMigration()`, which dropped every table on any
     * version bump. Some of what lives here exists nowhere else: `status_history` has no endpoint at
     * all, notes and bug reports whose push failed are device-only, and a lead's call history can only
     * be partly rebuilt (the backend can return answered calls, never missed or failed ones).
     *
     * The destructive fallback is kept only for pre-14 versions ([DESTRUCTIVE_FALLBACK_VERSIONS]) —
     * those builds wiped on upgrade regardless, so no data from them was ever preserved. Anything from
     * 14 onward must migrate. A missing migration now throws on open instead of silently deleting the
     * agent's work, which is the behaviour we want: a loud crash in testing beats quiet data loss on a
     * user's phone.
     */
    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CrmDatabase =
        Room.databaseBuilder(context, CrmDatabase::class.java, "crm_database")
            .addMigrations(*CRM_MIGRATIONS)
            .fallbackToDestructiveMigrationFrom(*DESTRUCTIVE_FALLBACK_VERSIONS)
            .build()

    @Provides fun provideLeadDao(db: CrmDatabase): LeadDao = db.leadDao()
    @Provides fun provideNoteDao(db: CrmDatabase): NoteDao = db.noteDao()
    @Provides fun provideStatusHistoryDao(db: CrmDatabase): StatusHistoryDao = db.statusHistoryDao()
    @Provides fun provideBugReportDao(db: CrmDatabase): BugReportDao = db.bugReportDao()
    @Provides fun provideCallDao(db: CrmDatabase): CallDao = db.callDao()
}
// nothing