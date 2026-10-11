<p align="center">
  <img src="docs/icon.png" alt="MB-VoiceVault" width="128" height="128">
</p>

<h1 align="center">MB-VoiceVault</h1>

<p align="center">
  Hold the power button, speak, pocket the phone. A compact Opus audio file
  appears in your chosen folder. No typing, fully offline.
</p>

MB-VoiceVault is an eyes-free voice-capture app for the moment an idea shows up and
reaching for a keyboard would lose it. Trigger it, talk, put the phone away. That
is the whole interaction.

## How it works

1. Hold the power button to launch MB-VoiceVault as your device assistant.
2. Speak. A short buzz confirms recording started.
3. Turn the screen off (pocket the phone) or tap once to stop. Silence also stops it
   after a few seconds.
4. The app saves audio locally first, then encodes it as mono Ogg Opus at 24 kbps
   and writes a timestamp-named `.ogg` file into your selected output folder.

Output is roughly 180 KB per minute, plus container overhead. If encoding or folder
writing fails, the local WAV remains and retries when you open the app, record again,
or select a reachable folder. Existing pending WAV recordings also export as Opus.

The separate Quick Note widget still writes typed Markdown notes. It does not
record audio or transcribe speech.

Feedback is haptic (distinct patterns for start, stop, and needs-attention) plus a
status notification. Setup requests permissions, selects the output folder and
sets MB-VoiceVault as the digital assistant. There is no model download.

## Privacy

MB-VoiceVault collects nothing. No account, no analytics, no advertising, no telemetry.
The app records and compresses audio entirely on the device. It has no internet
permission, transcription code or speech model. Files go into a folder you choose.
Any sync or upload performed by another app watching that folder is outside MB-VoiceVault.

## Requirements and scope

This is a Pixel-first app. The original recording flow was tested on a Pixel 6 Pro.
The new Opus export still needs device validation. It should install and run
on other reasonably recent phones, but two things are worth knowing before you try.

The power-button trigger depends on your phone letting the default assistant answer
the assist gesture. On stock Pixel and other close-to-AOSP builds this works once
MB-VoiceVault is set as the digital assistant. On many OEM skins (Samsung, Xiaomi,
OnePlus, and others) the power-button hold is wired to the maker's own power menu or
assistant and cannot be reassigned, so the gesture may do nothing. On those phones
the typed Quick Note widget remains available, but is not an audio recording fallback.

- Android 10 (API 29) or newer.
- `arm64-v8a` devices only. This covers essentially every phone from the last several
  years; older 32-bit-only devices are not supported.
- Any spoken language, since recordings are saved without transcription.
- Free space for pending WAV recordings and compressed output.
- An Opus-capable player to listen to the `.ogg` files.
- Permissions: microphone and notifications. No network permission.

## Install

The app is distributed as a signed APK on the [Releases](../../releases) page. Two
ways to install it:

### Obtainium (recommended, auto-updates)

[Obtainium](https://github.com/ImranR98/Obtainium) installs and auto-updates apps
directly from GitHub Releases. Add this repository's URL as an app source and
Obtainium will keep MB-VoiceVault up to date for you.

### Manual sideload

Download the latest `voice-vault-vX.Y.Z.apk` from
[Releases](../../releases) and open it on the device. You will need to allow
installing from unknown sources when prompted.

## Verifying the build

This is a privacy app, so you should be able to check it rather than trust it:

- **Watch what it does.** Run it behind a network monitor (PCAPdroid, NetGuard, a
  Pi-hole) and confirm it makes no network calls.
- **Check the artifact.** Each release ships a `.sha256` alongside the APK. Verify it
  with `sha256sum -c voice-vault-vX.Y.Z.apk.sha256`.
- **Read the source.** Every release is built from the tagged commit by the GitHub
  Actions workflow in `.github/workflows/release.yml`.

## Build from source

```bash
./gradlew assembleDebug          # debug APK
./gradlew installDebug           # build and install on a connected device
./gradlew test                   # JVM unit tests
./gradlew connectedAndroidTest   # instrumented tests (needs a device)
./gradlew lint                   # Android lint
```
