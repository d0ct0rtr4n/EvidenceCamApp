package com.evidencecam.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class EvidenceCamApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Recording Service Channel
        val recordingChannel = NotificationChannel(
            RECORDING_CHANNEL_ID,
            "Recording Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when video recording is active"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }

        // Upload Progress Channel
        val uploadChannel = NotificationChannel(
            UPLOAD_CHANNEL_ID,
            "Upload Progress",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows upload progress for video files"
            setShowBadge(false)
        }

        // Alerts Channel
        val alertsChannel = NotificationChannel(
            ALERTS_CHANNEL_ID,
            "Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Important alerts about storage and recording"
            enableVibration(true)
        }

        notificationManager.createNotificationChannels(
            listOf(recordingChannel, uploadChannel, alertsChannel)
        )
    }

    companion object {
        const val RECORDING_CHANNEL_ID = "recording_channel"
        const val UPLOAD_CHANNEL_ID = "upload_channel"
        const val ALERTS_CHANNEL_ID = "alerts_channel"
    }
}
