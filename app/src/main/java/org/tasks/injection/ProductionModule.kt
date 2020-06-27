package org.tasks.injection

import android.content.Context
import androidx.room.Room
import com.todoroo.astrid.dao.Database
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ApplicationComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tasks.BuildConfig
import org.tasks.R
import org.tasks.data.CaldavDao
import org.tasks.data.GoogleTaskListDao
import org.tasks.db.Migrations
import org.tasks.jobs.WorkManager
import org.tasks.jobs.WorkManagerImpl
import org.tasks.preferences.Preferences
import javax.inject.Singleton

@Module
@InstallIn(ApplicationComponent::class)
internal class ProductionModule {
    @Provides
    @Singleton
    fun getAppDatabase(@ApplicationContext context: Context, preferences: Preferences): Database {
        val builder = Room.databaseBuilder(context, Database::class.java, Database.NAME)
                .addMigrations(*Migrations.MIGRATIONS)
        if (!BuildConfig.DEBUG || preferences.getBoolean(R.string.p_debug_main_queries, true)) {
            builder.allowMainThreadQueries()
        }
        return builder.build()
    }

    @Provides
    fun getPreferences(@ApplicationContext context: Context): Preferences = Preferences(context)

    @Provides
    @Singleton
    fun getWorkManager(
            @ApplicationContext context: Context,
            preferences: Preferences,
            googleTaskListDao: GoogleTaskListDao,
            caldavDao: CaldavDao): WorkManager {
        return WorkManagerImpl(context, preferences, googleTaskListDao, caldavDao)
    }
}