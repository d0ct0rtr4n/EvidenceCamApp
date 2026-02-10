# Evidence Cam App for Android

Version 1.0.3

Continuous video recording with timestamp overlay and automatic cloud backup

## Features

- Continuous recording with configurable segments
- Automatic cloud upload (Dropbox)
- Smart storage management (auto-delete oldest when at capacity)
- Timestamp/GPS overlay on recordings
- Long press to stop recording
- Background recording support

## Development Setup

### Prerequisites

- **Android Studio** (recommended) — [Download](https://developer.android.com/studio)
- **JDK 17** or higher
- **Android SDK** — Min API 26, Target API 35

### Building the Project

1. **Clone the repository**
   ```bash
   git clone https://github.com/d0ct0rtr4n/EvidenceCamApp.git
   cd EvidenceCamApp
   ```

2. **Open in Android Studio**
   - File → Open → Select project directory
   - Android Studio will automatically sync Gradle dependencies

3. **Build and Run**
   - Use Android Studio's Run button, or
   - Build → Make Project
   - Build → Build Bundle(s) / APK(s) → Build APK(s)

### Alternative: Command Line Build

If you prefer building via command line, ensure Gradle is installed:
```bash
gradle assembleDebug      # Build debug APK
gradle installDebug       # Install on connected device
```

## Project Structure

```
EvidenceCamApp/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/evidencecam/app/
│       │   ├── EvidenceCamApplication.kt
│       │   ├── di/AppModule.kt
│       │   ├── model/Models.kt
│       │   ├── repository/
│       │   ├── service/
│       │   ├── ui/
│       │   ├── util/
│       │   └── viewmodel/
│       └── res/
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## Permissions

- **Camera** — video recording
- **Microphone** — audio capture
- **Location** — GPS overlay on recordings
- **Notifications** — recording status and upload progress
- **Foreground Service** — background recording
