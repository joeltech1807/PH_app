# Asterix Voice Audio Player

## 📲 [Download Asterix.apk (latest)](https://github.com/joeltech1807/PH_app/releases/latest/download/Asterix.apk)

Open this link on your Android phone, open the downloaded file, and allow installing when asked.
It always points to the newest version.


An offline Android app that plays your own audio files when you say voice commands such as
"play song1 in loop", "play song2 once", "pause" or "stop". Speech recognition (Vosk, small
English model), command parsing and playback (Media3 ExoPlayer) all run on the phone. The app
sends nothing to the internet.

## Build

Requirements: JDK 17 and an Android SDK with platform 35. This workspace was built with a
self-contained toolchain in `E:\android-toolchain`.

```
set JAVA_HOME=E:\android-toolchain\jdk-17.0.20.1+1
gradlew.bat testDebugUnitTest assembleDebug
```

The APK is written to `app\build\outputs\apk\debug\app-debug.apk`.

## Install

- **Over USB:** `E:\android-toolchain\sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk`
- **Manually:** copy the APK to the phone and open it. Allow "Install unknown apps" for your file manager when Android asks.

The app needs Android 8.0 (API 26) or newer.

## First use

1. **Library → Import audio.** Choose one or more files (MP3, WAV, M4A, AAC, OGG). Give each one a
   command name, for example `song1`. The app copies the file into its own storage, so moving or
   deleting the original later does not break the entry.
2. **Home → tap the big microphone button.** Allow **Microphone** and **Notifications** when asked.
   A notification that reads "🎤 Microphone active" appears and stays while the app is listening.
3. Say "play song one". You don't need a wake word. The command has to start the sentence
   (play / loop / repeat / stop / pause / resume ...).
4. To stop listening, tap the button again or use **Stop listening** in the notification.

If you denied the microphone permission permanently, open **Settings → Apps → Asterix → Permissions →
Microphone → Allow only while using the app**. Listening still works with the screen locked,
because it runs in a foreground service that you started.

## Keeping it listening in the background

Android may still stop background apps. To make that less likely:
- In the app, go to **Settings → Allow unrestricted battery use**.
- Follow the manufacturer-specific steps in the app's Settings screen (Samsung, Xiaomi, OnePlus/OPPO,
  Huawei, Vivo). See also dontkillmyapp.com.
- On Android 14 and newer, Android does not let a microphone service start from the background.
  Listening therefore never starts by itself after a reboot or after Android closes the app. The Home
  screen shows "Listening was stopped by the system" with a Restart button.
- While another app is using the microphone (a phone call, a voice recorder), Android gives this app
  silence. The app detects this and shows "Microphone in use by another app".

## Commands

| Say | Effect |
|---|---|
| play song1 · play song1 once · play song1 one time | Stops the current audio and plays song1 once from the start (once is the default) |
| play song1 in loop · play song2 continuously · play song1 on repeat | Stops the current audio and loops song1 from the start |
| loop song1 · repeat alarm1 | If song1 is already the current track, turns repeat on without restarting it. Otherwise starts it looping. |
| stop · stop playback · stop song1 | Stops playback immediately |
| pause / resume · continue playing | Pauses the current track / continues a paused track |
| play again | Resumes if paused, otherwise restarts the last track in its last mode |
| loop this song / stop looping · play only once | Changes the repeat mode of the current track |

You can say names as words or digits: "song one" and "song1" are the same name. Only exact matches
play. If a name is unknown, the app says "song3 was not found in your audio library" and plays
nothing. If a name is close to an existing one, the message suggests it, but the app does not play it.

## Architecture

| Component | File |
|---|---|
| Normalization (number words, alias keys, spoken forms) | `command/TextNormalizer.kt` |
| Deterministic command parser | `command/CommandParser.kt` |
| Playback decision policy (pure, unit-tested) | `command/CommandPolicy.kt` |
| Pipeline: speech → command → player, logging, TTS errors | `CommandProcessor.kt` |
| Audio library (copy into app storage, Room) | `data/AudioLibrary.kt`, `data/AudioDatabase.kt` |
| Settings (DataStore) | `data/SettingsStore.kt` |
| Playback (single ExoPlayer, audio focus, repeat-one) | `playback/PlaybackController.kt` |
| Continuous recognition (AudioRecord → Vosk, AEC, silence gate, retry) | `voice/VoiceEngine.kt`, `voice/ModelManager.kt` |
| Foreground service (microphone + mediaPlayback types, notification) | `service/AsterixService.kt` |
| Compose UI | `ui/` |
| Debug-only adb test hooks | `app/src/debug/.../DebugCommandReceiver.kt` |

### How the app avoids reacting to its own audio
- **Restricted vocabulary.** The recognizer only knows the command words and your audio names.
  Everything else is recognized as `[unk]`.
- **A verb must start the command.** Lyrics like "...we should play..." are ignored.
- **Echo cancellation.** The app records from the `VOICE_COMMUNICATION` microphone source with
  `AcousticEchoCanceler`, so the phone's own playback is subtracted where the device supports it.
- **Confidence check during playback.** While audio plays, commands below a confidence threshold
  are ignored. You can change the threshold in Settings.
- **Spoken error messages are ignored.** Anything heard while the app's own text-to-speech is
  talking is discarded.

## Licenses
The bundled speech model is [vosk-model-small-en-us-0.15](https://alphacephei.com/vosk/models) (Apache 2.0).
