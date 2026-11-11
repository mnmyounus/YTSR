# 🎬 YTSR — Your TV Screen Recorder

**Zero-Network Privacy by Design** | Android TV | Screen Recording | Kotlin

---

## 📖 Overview

**YTSR** is a lightweight, privacy-first screen recording app for Android TV. It records your TV screen + audio to MP4 files stored locally on your device — **with zero internet access**.

### Key Features

✅ **Remote Control Integration** — Double-press remote buttons `[0]` and `[1]` to START/STOP recording  
✅ **Accessibility Service** — Records globally, even when other apps are in the foreground  
✅ **Zero Network Access** — No permissions for internet; all data stays on your device  
✅ **H.264/AAC Codec** — HD (30 fps) video + stereo audio at 44.1 kHz  
✅ **TV-Optimized UI** — D-pad navigation, large text (≥18sp), readable at 10-foot distance  
✅ **API 23+** — Supports Android 6.0 and newer TV devices  

---

## 🚀 Getting Started

### Prerequisites

- Android TV (API 23+)
- Microphone permission (for audio)
- Accessibility Service enabled in settings

### Installation

1. Build the debug APK:
   ```bash
   ./gradlew assembleDebug
   ```

2. Install on your TV:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### Initial Setup

1. **Launch YTSR** from your TV home screen
2. **Grant permissions** when prompted
3. **Enable Accessibility Service**:
   - Tap "Enable in TV Settings" button
   - Navigate: Settings → Accessibility → YTSR Key Interceptor → ON
4. **Open a media app** (e.g., Peo TV) and start recording

---

## 📹 How to Use

### Start Recording

1. Open any app on your TV (e.g., Peo TV)
2. **Double-press `[0]`** quickly on your remote
3. Approve the system capture dialog
4. Recording starts 🔴

### Stop Recording

1. **Double-press `[1]`** quickly on your remote
2. Recording stops and file is saved ✅

### Find Your Videos

**API 29+** (Android 10+)
```
/sdcard/Android/data/com.younus.ytsr/files/Recordings/
```

**API ≤ 28** (Android 9 and below)
```
/sdcard/Movies/YTSR/
```

Videos are named: `YTSR_YYYYMMdd_HHmmss.mp4`

---

## 🏗️ Architecture

### Services & Components

| Component | Role |
|-----------|------|
| **MainActivity** | Setup wizard; permission/accessibility status display |
| **YTSRAccessibilityService** | Intercepts remote key events (`[0]`, `[1]`) globally |
| **RecordingService** | Manages screen capture, MediaRecorder lifecycle |
| **MediaProjectionRequestActivity** | Transparent activity to display capture consent dialog |

### Recording Flow

```
[Remote Button Press]
         ↓
YTSRAccessibilityService detects double-press
         ↓
triggerStart() / triggerStop()
         ↓
RecordingService.onStartCommand()
         ↓
MediaRecorder + VirtualDisplay setup
         ↓
H.264 video + AAC audio → MP4 file
```

---

## 🔧 Technical Stack

- **Language**: Kotlin
- **Minimum SDK**: 23 (Android 6.0)
- **Target SDK**: 35 (Android 15)
- **Gradle**: 8.7
- **Android Gradle Plugin**: 8.6.0
- **Build System**: Gradle
- **UI Framework**: Android Leanback (TV)

### Key Dependencies

```gradle
androidx.core:core-ktx:1.13.1
androidx.leanback:leanback:1.2.0
androidx.fragment:fragment-ktx:1.7.0
androidx.lifecycle:lifecycle-runtime-ktx:2.8.0
```

---

## 🔐 Privacy & Permissions

### Permissions Used

| Permission | Purpose | Notes |
|------------|---------|-------|
| `RECORD_AUDIO` | Captures microphone | Required |
| `WRITE_EXTERNAL_STORAGE` | Saves videos | API ≤ 28 only |
| `READ_EXTERNAL_STORAGE` | Reads storage | API ≤ 32 only |
| `FOREGROUND_SERVICE` | Background recording | Required |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Media projection type | Required |
| `BIND_ACCESSIBILITY_SERVICE` | Remote interception | Required |

### Permissions NOT Used

- ❌ `INTERNET` — Explicitly blocked in CI/CD
- ❌ `CAMERA` — Only uses screen capture
- ❌ `CONTACTS` — No personal data access
- ❌ `LOCATION` — No location services

---

## 🛠️ Development

### Project Structure

```
YTSR/
├── app/
│   ├── src/main/
│   │   ├── java/com/younus/ytsr/
│   │   │   ├── MainActivity.kt
│   │   │   ├── YTSRAccessibilityService.kt
│   │   │   ├── RecordingService.kt
│   │   │   └── MediaProjectionRequestActivity.kt
│   │   ├── res/
│   │   │   ├── layout/activity_main.xml
│   │   │   ├── drawable/
│   │   │   │   ├── app_banner.xml
│   │   │   │   ├── ic_record.xml
│   │   │   │   └── ic_stop.xml
│   │   │   ├── values/values.xml
���   │   │   └── xml/accessibility_service_config.xml
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── gradle/wrapper/gradle-wrapper.properties
├── build.gradle
├── settings.gradle
└── README.md
```

### Building

```bash
# Debug build
./gradlew assembleDebug

# Run build with stack trace
./gradlew assembleDebug --stacktrace --no-daemon

# Clean build
./gradlew clean assembleDebug
```

### Testing on Emulator/Device

```bash
# List connected devices/emulators
adb devices

# Install app
adb install -r app/build/outputs/apk/debug/app-debug.apk

# View logs
adb logcat | grep -E "YTSR|Recording"

# Filter by tag
adb logcat | grep "RecordingService"
adb logcat | grep "YTSRAccessibility"
```

---

## 📋 CI/CD Pipeline

**GitHub Actions** workflow (`.github/workflows/release.yml`):

1. ✅ Verify no INTERNET permission
2. ✅ Build debug APK
3. ✅ Create GitHub Release
4. ✅ Upload APK as release asset

---

## 🐛 Troubleshooting

### Videos not saving?

**Issue**: Recording completes but no MP4 file is created.

**Solution**: 
- Ensure `recorder.release()` is called **immediately after** `recorder.stop()` (line 147 in RecordingService.kt)
- Without `release()`, the MP4 container is not finalized
- Check Android logs: `adb logcat | grep RecordingService`

**What to look for in logs:**
```
RecordingService: ■ Saved → /sdcard/Android/data/com.younus.ytsr/files/Recordings/YTSR_20260610_121530.mp4
```

### Accessibility Service not working?

**Issue**: Remote button presses not being detected.

**Solution**:
1. Ensure service is enabled: Settings → Accessibility → YTSR Key Interceptor → ON
2. Check if service is running: `adb logcat | grep YTSRAccessibility`
3. Verify permissions are granted: Settings → Apps → Permissions

**What to look for in logs:**
```
YTSRAccessibility: Service connected — key filtering ACTIVE
YTSRAccessibility: ⚡ [0][0] double-press (Δ245ms) → START recording
```

### Permission issues?

**Issue**: App crashes or permission denied errors.

**Solution**:
- Grant permissions manually: Settings → Apps → YTSR → Permissions
- Check manifest for required permissions
- Verify `WRITE_EXTERNAL_STORAGE` only declared for API ≤ 28

---

## 📝 License

This project is provided as-is for educational and personal use.

---

## 👤 Developer

**YOUNUS**  
Created: June 2026

---

## 🙏 Acknowledgments

- Android Accessibility Service API
- MediaProjection API
- Android Leanback (TV framework)
- Kotlin language

---

## 📞 Support & Debugging

For quick diagnostics, run:

```bash
adb logcat -s "RecordingService:*" "YTSRAccessibility:*" "MainActivity:*" | grep -E "ERROR|FAILED|Recording|double-press|Saved"
```

---
## Contributors

- MNM YOUNUS (https://github.com/mnmyounus) - Maintainer
- **Claude** ([Anthropic](https://anthropic.com)) - AI Assistant & Code Generation

**Made with ❤️ for Android TV**
