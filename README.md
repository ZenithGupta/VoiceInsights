# VoiceInsights

A privacy-first Android app that continuously records ambient audio 24/7 in the background, compresses it into lightweight `.m4a` chunks, and uploads them to Google Drive for offline AI transcription and insights.

## Tech Stack
| Component | Technology |
|-----------|-----------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Background | Android Foreground Service |
| Audio | MediaRecorder (AAC, 64kbps, Mono) |
| Cloud | Google Drive REST API |
| Architecture | MVVM |

## Features
- **24/7 Background Recording** via Android Foreground Service with persistent notification
- **Compressed Audio Chunks** — 10-minute `.m4a` files (~4MB each) instead of raw PCM (~100MB each)
- **Google Drive Sync** — Automatic upload of audio chunks (in progress)
- **Phone Call Import** — Auto-detect and upload call recordings from native dialer or Cube ACR (planned)

## Permissions
- `RECORD_AUDIO` — Microphone access
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE` — Background recording
- `POST_NOTIFICATIONS` — Persistent notification (Android 13+)
- `INTERNET` — Google Drive uploads
- `READ_PHONE_STATE` — Call state detection

## Project Structure
```
app/src/main/java/com/example/voiceinsights/
├── MainActivity.kt          # Entry point
├── MainScreen.kt            # Compose UI (permissions + start/stop)
├── RecordingService.kt      # Foreground Service
└── AudioCaptureManager.kt   # MediaRecorder chunking engine
```

## Setup
1. Clone the repo
2. Open in Android Studio (Meerkat+)
3. Sync Gradle
4. Run on a physical Android device (emulator mic support is limited)

## License
Private — Personal use only.
