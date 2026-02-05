package com.evidencecam.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

object NetworkUtils {
    
    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    
    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
    
    fun canUpload(context: Context, wifiOnly: Boolean): Boolean =
        if (wifiOnly) isWifiConnected(context) else isNetworkAvailable(context)
}

object FormatUtils {
    private val fileSizeFormat = DecimalFormat("#,##0.#")
    
    fun formatFileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${fileSizeFormat.format(bytes / 1024.0)} KB"
        bytes < 1024 * 1024 * 1024 -> "${fileSizeFormat.format(bytes / (1024.0 * 1024))} MB"
        else -> "${fileSizeFormat.format(bytes / (1024.0 * 1024 * 1024))} GB"
    }
    
    fun formatDuration(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return if (hours > 0) String.format("%02d:%02d:%02d", hours, minutes, seconds)
        else String.format("%02d:%02d", minutes, seconds)
    }
    
    fun formatTimestamp(timestamp: Long): String =
        SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

object Constants {
    const val SEGMENT_DURATION_DEFAULT = 2
    const val MAX_STORAGE_PERCENT_DEFAULT = 90
    const val UPLOAD_RETRY_MAX = 3
    const val UPLOAD_WORK_TAG = "video_upload_work"
    
    const val ACTION_START_RECORDING = "com.evidencecam.app.START_RECORDING"
    const val ACTION_STOP_RECORDING = "com.evidencecam.app.STOP_RECORDING"
    const val ACTION_RECORDING_STARTED = "com.evidencecam.app.RECORDING_STARTED"
    const val ACTION_RECORDING_STOPPED = "com.evidencecam.app.RECORDING_STOPPED"
    const val ACTION_SEGMENT_COMPLETED = "com.evidencecam.app.SEGMENT_COMPLETED"
    const val ACTION_ERROR = "com.evidencecam.app.ERROR"
    
    const val NOTIFICATION_ID_RECORDING = 1001
    const val NOTIFICATION_ID_UPLOAD = 1002
}

open class Event<out T>(private val content: T) {
    var hasBeenHandled = false
        private set
    
    fun getContentIfNotHandled(): T? =
        if (hasBeenHandled) null
        else { hasBeenHandled = true; content }
    
    fun peekContent(): T = content
}
