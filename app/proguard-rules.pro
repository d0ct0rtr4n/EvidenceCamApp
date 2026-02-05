# Add project specific ProGuard rules here.

# Keep data classes
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep Room entities
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers class * {
    @androidx.room.* <fields>;
    @androidx.room.* <methods>;
}

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel { *; }

# Coroutines
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# CameraX
-keep class androidx.camera.** { *; }

# Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Guava
-dontwarn com.google.common.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**

# Dropbox SDK
-keep class com.dropbox.core.** { *; }
-dontwarn com.dropbox.core.**
-keepattributes Signature
-keepattributes *Annotation*

# OkHttp (used by Dropbox SDK and others)
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

