# TTS Playback (AI Reply Read-Aloud)

OpenCode Android reads assistant replies aloud using the platform **TextToSpeech** engine, backed by a **foreground `TtsService`** so playback continues when the app is backgrounded or the screen is locked.

## Architecture

```
MainViewModel
    │  session.status (not busy) → maybeAutoReadLastAssistant()
    │  playMessage() / stopTts()
    ▼
TtsController (@Singleton)
    │  stripMarkdown() + startForegroundService()
    ▼
TtsService (foregroundServiceType=mediaPlayback)
    ├── TextToSpeech (system engine)
    ├── MediaSession (lock-screen / notification controls)
    └── NotificationCompat (pause / stop actions)
```

## Product rules

| Decision | Behavior |
|----------|----------|
| Auto-read default | **ON** (`SettingsManager.autoReadAloud`, default `true`) |
| New reply vs. in-progress playback | **Preempt** — new text stops the current utterance and starts the new one |
| STT vs. TTS | **Mutually exclusive** — starting TTS stops an active STT recording (`stopSpeechForBackground()`) |
| Background | TTS continues via foreground service; STT still stops on `ON_STOP` (unchanged) |

## Text source

Assistant message plain text is extracted from text parts:

```kotlin
message.parts.filter { it.isText }.mapNotNull { it.text }.joinToString("\n")
```

`TtsController.stripMarkdown()` removes common Markdown markers (`#`, `**`, `` ` ``, `[text](url)`, code fences) before speaking.

## UI surfaces

- **Auto-read**: triggered after SSE `session.status` transitions to not-busy for the current session (500 ms delay for message refresh).
- **Manual control**: each assistant message row shows a speaker icon; active playback shows stop/pause affordance.
- **Settings → Text-to-Speech**: “Auto-read AI replies” toggle.

## Manual test checklist

1. Install debug build (`path-b-grapeot` / `ai.opencode.client`).
2. Send a prompt; confirm the assistant reply is read aloud automatically.
3. Background the app or lock the screen; confirm playback continues and the notification shows pause/stop.
4. Send a second prompt while the first is still playing; confirm the new reply replaces the old playback.
5. Start STT recording, then receive/trigger TTS; confirm recording stops.
6. Disable “Auto-read AI replies” in Settings; confirm new replies are silent until the speaker icon is tapped.

## Related docs

- Speech input (STT): VoiceFlowKit integration in `MainViewModel` / Settings → Speech Recognition.
