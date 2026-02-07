# Evidence Cam App for Android

Version 1.0.2

Continuous video recording with timestamp overlay and automatic cloud backup

## Features

- Continuous recording with configurable segments
- Automatic cloud upload (Dropbox)
- Smart storage management (auto-delete oldest when at capacity)
- Timestamp/GPS overlay on recordings
- Auto-start recording on app launch
- Long press to stop recording
- Background recording support

## VS Code Setup

### 1. Install Prerequisites

- **JDK 17**: Download from [Adoptium](https://adoptium.net/)
- **Android Studio**: For SDK tools (or standalone SDK)
- **VS Code Extensions**: Kotlin, Gradle for Java

### 2. Configure Environment

```bash
# Add to your shell profile
export ANDROID_HOME=$HOME/Android/Sdk
export JAVA_HOME=/path/to/jdk-17
export PATH=$PATH:$ANDROID_HOME/platform-tools
```

### 3. Create local.properties

Create `local.properties` in project root:
```properties
sdk.dir=/path/to/Android/Sdk
```

### 4. Build & Run

```bash
# Make gradlew executable (Mac/Linux)
chmod +x gradlew

# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug
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
