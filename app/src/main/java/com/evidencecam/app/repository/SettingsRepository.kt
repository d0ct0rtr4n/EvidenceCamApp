package com.evidencecam.app.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.evidencecam.app.model.*
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "evidencecam_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()

    companion object {
        private val UPLOAD_DESTINATION = stringPreferencesKey("upload_destination")
        private val VIDEO_QUALITY = stringPreferencesKey("video_quality")
        private val SEGMENT_DURATION = stringPreferencesKey("segment_duration")
        private val MAX_STORAGE_PERCENT = intPreferencesKey("max_storage_percent")
        private val UPLOAD_ON_WIFI_ONLY = booleanPreferencesKey("upload_on_wifi_only")
        private val ENABLE_AUDIO = booleanPreferencesKey("enable_audio")
        private val AUTO_DELETE_AFTER_UPLOAD = booleanPreferencesKey("auto_delete_after_upload")
        private val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        private val SHOW_PREVIEW = booleanPreferencesKey("show_preview")
        private val AUTO_START_RECORDING = booleanPreferencesKey("auto_start_recording")
        private val PRIVACY_POLICY_ACCEPTED = booleanPreferencesKey("privacy_policy_accepted")
        private val DROPBOX_CONFIG = stringPreferencesKey("dropbox_config")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        AppSettings(
            uploadDestination = preferences[UPLOAD_DESTINATION]?.let {
                runCatching { UploadDestination.valueOf(it) }.getOrDefault(UploadDestination.LOCAL_ONLY)
            } ?: UploadDestination.LOCAL_ONLY,
            videoQuality = preferences[VIDEO_QUALITY]?.let {
                VideoQuality.valueOf(it)
            } ?: VideoQuality.HD,
            segmentDuration = preferences[SEGMENT_DURATION]?.let {
                runCatching { SegmentDuration.valueOf(it) }.getOrNull()
            } ?: SegmentDuration.MIN_2,
            maxStoragePercent = preferences[MAX_STORAGE_PERCENT] ?: 90,
            uploadOnWifiOnly = preferences[UPLOAD_ON_WIFI_ONLY] ?: false,
            enableAudio = preferences[ENABLE_AUDIO] ?: true,
            autoDeleteAfterUpload = preferences[AUTO_DELETE_AFTER_UPLOAD] ?: false,
            keepScreenOn = preferences[KEEP_SCREEN_ON] ?: true,
            showPreview = preferences[SHOW_PREVIEW] ?: true,
            autoStartRecording = preferences[AUTO_START_RECORDING] ?: false
        )
    }

    val privacyPolicyAcceptedFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PRIVACY_POLICY_ACCEPTED] ?: false
    }

    val dropboxConfigFlow: Flow<DropboxConfig?> = context.dataStore.data.map { preferences ->
        preferences[DROPBOX_CONFIG]?.let {
            runCatching { gson.fromJson(it, DropboxConfig::class.java) }.getOrNull()
        }
    }

    suspend fun updateUploadDestination(destination: UploadDestination) {
        context.dataStore.edit { it[UPLOAD_DESTINATION] = destination.name }
    }

    suspend fun updateVideoQuality(quality: VideoQuality) {
        context.dataStore.edit { it[VIDEO_QUALITY] = quality.name }
    }

    suspend fun updateSegmentDuration(duration: SegmentDuration) {
        context.dataStore.edit { it[SEGMENT_DURATION] = duration.name }
    }

    suspend fun updateMaxStoragePercent(percent: Int) {
        context.dataStore.edit { it[MAX_STORAGE_PERCENT] = percent.coerceIn(50, 95) }
    }

    suspend fun updateUploadOnWifiOnly(wifiOnly: Boolean) {
        context.dataStore.edit { it[UPLOAD_ON_WIFI_ONLY] = wifiOnly }
    }

    suspend fun updateEnableAudio(enabled: Boolean) {
        context.dataStore.edit { it[ENABLE_AUDIO] = enabled }
    }

    suspend fun updateAutoDeleteAfterUpload(autoDelete: Boolean) {
        context.dataStore.edit { it[AUTO_DELETE_AFTER_UPLOAD] = autoDelete }
    }

    suspend fun updateKeepScreenOn(keepOn: Boolean) {
        context.dataStore.edit { it[KEEP_SCREEN_ON] = keepOn }
    }

    suspend fun updateShowPreview(show: Boolean) {
        context.dataStore.edit { it[SHOW_PREVIEW] = show }
    }

    suspend fun updateAutoStartRecording(autoStart: Boolean) {
        context.dataStore.edit { it[AUTO_START_RECORDING] = autoStart }
    }

    suspend fun acceptPrivacyPolicy() {
        context.dataStore.edit { it[PRIVACY_POLICY_ACCEPTED] = true }
    }

    suspend fun saveDropboxConfig(config: DropboxConfig?) {
        context.dataStore.edit { prefs ->
            if (config != null) {
                prefs[DROPBOX_CONFIG] = gson.toJson(config)
            } else {
                prefs.remove(DROPBOX_CONFIG)
            }
        }
    }
}
