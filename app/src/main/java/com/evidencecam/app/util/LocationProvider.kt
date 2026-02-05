package com.evidencecam.app.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double?,
    val speed: Float?,
    val timestamp: Long
) {
    fun formatCoordinates(): String {
        val latDir = if (latitude >= 0) "N" else "S"
        val lonDir = if (longitude >= 0) "E" else "W"
        return String.format(
            java.util.Locale.US,
            "%.6f%s %.6f%s",
            kotlin.math.abs(latitude), latDir,
            kotlin.math.abs(longitude), lonDir
        )
    }

    fun formatForOverlay(): String {
        return formatCoordinates()
    }
}

@Singleton
class LocationProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _currentLocation = MutableStateFlow<LocationData?>(null)
    val currentLocation: StateFlow<LocationData?> = _currentLocation.asStateFlow()

    private var locationCallback: LocationCallback? = null
    private var isTracking = false

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        1000L // Update every second
    ).apply {
        setMinUpdateIntervalMillis(500L)
        setWaitForAccurateLocation(false)
    }.build()

    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (!hasLocationPermission() || isTracking) return

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    _currentLocation.value = location.toLocationData()
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
            isTracking = true

            // Try to get last known location immediately
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    if (_currentLocation.value == null) {
                        _currentLocation.value = it.toLocationData()
                    }
                }
            }
        } catch (e: SecurityException) {
            // Permission was revoked
            isTracking = false
        }
    }

    fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        locationCallback = null
        isTracking = false
    }

    @SuppressLint("MissingPermission")
    fun locationFlow(): Flow<LocationData?> = callbackFlow {
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    trySend(location.toLocationData())
                }
            }
        }

        if (hasLocationPermission()) {
            try {
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    callback,
                    Looper.getMainLooper()
                )
            } catch (e: SecurityException) {
                // Permission revoked
            }
        }

        awaitClose {
            fusedLocationClient.removeLocationUpdates(callback)
        }
    }

    private fun Location.toLocationData(): LocationData {
        return LocationData(
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            altitude = if (hasAltitude()) altitude else null,
            speed = if (hasSpeed()) speed else null,
            timestamp = time
        )
    }
}
