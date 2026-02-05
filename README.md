# Evidence Cam App for Android

A professional evidence camera application for Android with automatic cloud upload capabilities.

## Latest Stable Versions (January 2025)

| Component | Version |
|-----------|---------|
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
| Gradle | 8.9 |
| CameraX | 1.4.1 |
| Hilt | 2.54 |
| Room | 2.6.1 |
| Lifecycle | 2.8.7 |
| Coroutines | 1.9.0 |
| compileSdk | 35 |
| targetSdk | 35 |
| minSdk | 26 |
| Java | 17 |

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

## Google Play Store Requirements

✅ Privacy Policy included
✅ All permissions declared
✅ Target SDK 35
✅ 64-bit compatible

## Troubleshooting

**"SDK location not found"**: Create `local.properties` with your SDK path

**Build errors**: Run `./gradlew --refresh-dependencies`

**Camera permission denied**: Grant permissions in device settings
