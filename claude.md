# Evidence Cam Android App

A professional Android evidence camera application for continuous video recording with automatic cloud upload capabilities.

## Tech Stack

- **Language**: Kotlin 2.0.21
- **Min SDK**: 26 / **Target SDK**: 35
- **Architecture**: MVVM with Repository pattern
- **DI**: Hilt
- **Async**: Coroutines + Flow/StateFlow
- **Database**: Room
- **Preferences**: DataStore
- **Camera**: CameraX 1.4.1
- **Video Processing**: Media3 Transformer
- **Background Work**: WorkManager
- **Cloud**: Dropbox SDK
- **Navigation**: Navigation Component 2.8.5

## Project Structure

```
app/src/main/java/com/evidencecam/app/
├── di/              # Hilt dependency injection
├── model/           # Data classes and enums
├── repository/      # Data access (Room DB, DataStore)
├── service/         # RecordingService, UploadWorker
├── ui/
│   ├── gallery/     # GalleryActivity, VideoRecordingAdapter
│   ├── main/        # MainActivity, PrivacyPolicyActivity
│   ├── overlay/     # VideoOverlayView
│   └── settings/    # SettingsActivity
├── viewmodel/       # MVVM ViewModels
└── util/            # Helpers (location, overlay, Dropbox auth)
```

## Key Components

- **RecordingService**: Foreground service for CameraX video capture with segmented recording
- **UploadWorker**: WorkManager-based cloud upload with retry logic
- **VideoOverlayProcessor**: Adds timestamp/GPS overlay using Media3
- **VideoOverlayView**: Custom view for live overlay preview during recording
- **LocationProvider**: Fused location for GPS tracking
- **DropboxAuthHelper**: Handles Dropbox OAuth2 PKCE authentication flow

## Build Commands

```bash
./gradlew assembleDebug    # Build debug APK
./gradlew installDebug     # Install on device
./gradlew build            # Full build with tests
```

## Coding Conventions

- Classes: PascalCase
- Functions: camelCase
- Constants: UPPER_SNAKE_CASE
- Private StateFlow: prefix with underscore (`_recordingState`)
- Use `viewModelScope` for ViewModel coroutines
- Use `lifecycleScope` for Activity/Service coroutines
- Use `Dispatchers.IO` for database/file operations

## Important Patterns

- **State Management**: StateFlow for UI state, SharedFlow for events
- **Error Handling**: Sealed classes for typed errors, `runCatching` for exceptions
- **Permissions**: Runtime checks required for camera, audio, location, notifications
- **Service Binding**: RecordingService uses bound service pattern for preview
