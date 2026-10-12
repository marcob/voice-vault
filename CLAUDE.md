# MB-VoiceVault

Android app `dev.cazimir.voicevault` records eyes-free audio through the digital assistant gesture. It exports mono Ogg Opus at 24 kbps into a SAF-selected output folder. No transcription, model download or INTERNET permission.

## Commands

```bash
./gradlew assembleDebug
./gradlew installDebug
./gradlew test
./gradlew connectedAndroidTest
./gradlew lint
```

Android 10 or newer is required. The original capture flow was verified on a Pixel 6 Pro. Native Opus export needs physical device validation. Do not mark human/device tasks complete from code inspection.

## Git workflow

- Never create branches. Work directly on `main`.
- No PRs.
- Stage deliberately. Untracked specs or instructions may belong to another session.
- Do not commit or push unless asked.

## OpenSpec

Use the `openspec-*` skills to manage changes. The current audio-export change is `openspec/changes/save-opus-audio/`. Archived designs describe historical behavior; the audio-export design supersedes transcription assumptions.

## Architecture

```text
assistant gesture
  -> assistant/ starts RecordingService
  -> capture/ records PCM16 mono, 16 kHz
  -> vault/ atomically persists WAV in filesDir/pending/
  -> audio/ serializes native Opus encoding
  -> vault/ atomically publishes .ogg through SAF
  -> pending WAV and diagnostic removed only after success
```

- `assistant/` owns system assistant integration. The recognition service stub satisfies Android's assistant role contract; it does not transcribe.
- `capture/` owns microphone, haptics, audio focus and stop conditions. Recording stops on screen-off, a tap, five seconds of RMS silence or a three-minute cap.
- `audio/OpusEncoder` uses Android MediaCodec and MediaMuxer. No bundled codec library.
- `audio/AudioExportQueue` serializes exports while recording remains independent. Missing files are already handled; existing final output makes interrupted retries idempotent.
- `vault/` owns pending files, SAF grants, atomic document writes and timestamp naming. Existing preference keys remain to preserve folder selection on upgrade.
- `notify/` reports recording status and persistent export/folder errors.
- `MainActivity` requests microphone/notification permissions, selects an output folder and sets the assistant role. Launch and folder selection retry pending audio.
- `quicknote/` is an independent typed Markdown feature. Its widget does not record audio. Existing text notes are preserved.
- `Config.kt` contains capture constants, Opus bitrate and typed-note frontmatter.

## Invariants

- Start haptic fires only after AudioRecord is capturing.
- Persist WAV before encoding/export. Never delete pending audio on failure.
- Output writes use temporary document plus rename, never a direct write to the final name.
- Recording must remain usable while prior export runs. Names must not collide with pending WAV, existing Ogg or typed notes.
- Keep screen-off stop and overlay FLAG_KEEP_SCREEN_ON together.
- Audio focus is reference-counted across recordings and released before export.
- Do not reintroduce transcription, network access or model downloads.

Distribution remains a signed APK through `.github/workflows/release.yml`. This repository has no PR/test CI. Device gesture, playback and haptic checks require a physical device.
