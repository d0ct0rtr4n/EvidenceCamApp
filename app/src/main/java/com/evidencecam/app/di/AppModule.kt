package com.evidencecam.app.di

import android.content.Context
import androidx.room.Room
import com.evidencecam.app.repository.AppDatabase
import com.evidencecam.app.repository.VideoRecordingDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        ).build()
    }
    
    @Provides
    @Singleton
    fun provideVideoRecordingDao(database: AppDatabase): VideoRecordingDao {
        return database.videoRecordingDao()
    }
}
