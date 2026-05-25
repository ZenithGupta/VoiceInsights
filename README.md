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

## Audio Processing & Sarvam AI Integration (Python)

A set of Python scripts is provided in the `scripts/` directory to process audio uploaded to Google Drive.

### Features
1. **Silence Removal**: Uses the Silero VAD machine learning model to strip out silent background noise, preserving only actual speech.
2. **Auto-Upload**: Converts the processed audio back to `.m4a` and uploads it directly back into your Google Drive in the original folder.
3. **Sarvam API**: Transcribes the processed audio directly using Sarvam AI's Speech-to-Text API.

### Setup Instructions
1. Install [Python 3.8+](https://www.python.org/downloads/) on your computer.
2. Install **FFmpeg** on your computer and make sure it is added to your system's PATH (required for converting audio to `.m4a`).
3. Open a terminal and navigate to the `scripts/` folder inside the project.
4. Install the required Python packages:
   ```bash
   pip install -r requirements.txt
   ```
5. Get Google Drive API Credentials:
   - Go to the [Google Cloud Console](https://console.cloud.google.com/).
   - Enable the **Google Drive API**.
   - Create OAuth 2.0 Client ID credentials (Desktop app).
   - Download the JSON file, rename it to `credentials.json`, and place it inside the `scripts/` folder.
6. (Optional) For transcription, set your Sarvam AI Pro Plan API key as an environment variable:
   - Windows (Command Prompt): `set SARVAM_API_KEY=your_api_key_here`
   - Windows (PowerShell): `$env:SARVAM_API_KEY="your_api_key_here"`
   - Mac/Linux: `export SARVAM_API_KEY="your_api_key_here"`

### Running the Script
Run the script to automatically fetch all `.m4a` files from the last 45 days, clean them, re-upload them to Drive, and transcribe them:
```bash
python process_audio.py
```
*(On first run, a browser window will open asking you to log into your Google Account to grant Drive access.)*
