# مطراش (Matrash) — Android AI Voice Assistant · Engineering Notes

Native Android assistant (Kotlin) with an authentic Al-Ain Bedouin Emirati persona,
powered by **Google Gemini Live** (real-time speech-to-speech) with device control,
screen reading, wake-word, and accessibility focus (elderly / visually-impaired).

## Build & sign (this environment)
- No Gradle wrapper download (proxy 403). Use system gradle: `gradle :app:assembleDebug`
  with `ANDROID_HOME=/opt/android-sdk`, `local.properties` → `sdk.dir=/opt/android-sdk`.
- Sign with `apksigner` + the committed keystore `android-assistant/keystore/debug.keystore`
  (pass `android`, alias `androiddebugkey`), then `zipalign`. It lives in the repo so the
  signature is STABLE across container resets (SHA-256 `4d289e8d…`) — always use it so the
  user installs over the top without uninstalling. Do NOT generate a new keystore (that
  changes the signature and forces a one-time uninstall).
- If the container was reset, the Android SDK is gone. Re-provision: download
  `commandlinetools-linux-<ver>_latest.zip` (note: NO hyphen in "commandlinetools"; get the
  current `<ver>` from `https://dl.google.com/android/repository/repository2-3.xml`) into
  `/opt/android-sdk/cmdline-tools/latest/`, then `sdkmanager --licenses` and
  `sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"`. Recreate
  `android-assistant/local.properties` → `sdk.dir=/opt/android-sdk`.
- `cd` into `android-assistant/` before gradle — the shell cwd resets to repo root between
  Bash calls, which makes gradle fail with "does not contain a Gradle build".
- Before committing, scan the staged diff for secrets (Anthropic `sk-ant-…`, ElevenLabs
  `sk_…`, Gemini `AIza…`/`AQ.Ab8…`). Keys live only in git-ignored `secrets.properties`.

## Gemini Live API (hard-won gotchas)
- Model: `gemini-2.5-flash-native-audio-latest`. WS URL
  `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=<KEY>`
  (also send `x-goog-api-key` header). `AQ.Ab8…` keys work as `?key=`.
- **Native-audio model REJECTS `speechConfig.languageCode` (e.g. `ar-XA`)** → closes with
  `1007 Unsupported language code`. OkHttp then surfaces it 20 s later as a misleading
  "sent ping but didn't receive pong". Fix: set only `voiceConfig.prebuiltVoiceConfig.voiceName`
  (e.g. `Charon`), NO languageCode. Language comes from the system instruction + speech.
- Native-audio supports **only `AUDIO`** response modality (asking for `TEXT` → 1007).
- **Vision works**: send screenshots as `clientContent` user-turn `inlineData` (image/jpeg,
  base64). It reads full phone-resolution screenshots and OCRs text reliably. Send image +
  the request in ONE `turnComplete:true` turn. Multiple frames ≈ understanding a video.
- No `pingInterval` on the OkHttp client — the audio stream keeps the socket alive; a ping
  race was killing good sessions.
- Surface close reasons in `onClosing` so setup errors are visible.

## Audio routing (critical)
- **Do NOT use `MODE_IN_COMMUNICATION` or `VOICE_COMMUNICATION` mic source.** That puts the
  system in "call" state → other apps (WhatsApp voice notes) fail with "can't record during
  a call", and routes our voice to the earpiece ("no sound" on a phone held normally).
- Use `MediaRecorder.AudioSource.MIC` (16 kHz PCM16 in) + `AudioTrack` with
  `USAGE_MEDIA` (24 kHz PCM16 out) → plays on loudspeaker/Bluetooth in normal mode, no
  phantom call. Attach `AcousticEchoCanceler` + `NoiseSuppressor` to the record session.
- Always release AudioRecord/AudioTrack on EVERY end path (idle, onFailure, onClosing,
  onClosed) via one `endSession()` — abnormal closes used to leak the mic / stick audio mode.

## Screen reading
- The **assist API** (`VoiceInteractionSession` `onHandleAssist`/`onHandleScreenshot`) only
  yields *button labels* for modern apps (X/Twitter, TikTok, social) — NOT the real content.
- Real content (photos, video, social feeds) needs **MediaProjection** (actual pixels), then
  send the JPEG to Gemini vision.
- Capture **on-demand only**: create the `VirtualDisplay`+`ImageReader`, grab a short burst
  of frames, then release immediately. A *continuous* VirtualDisplay drains battery AND
  conflicts with the voice audio — the user rejected it twice. One-time grant via a
  transparent `ProjectionRequestActivity`; grant is lost on reboot (must re-enable).

## Voice session must never be blocked by capture
- Connect the Gemini session IMMEDIATELY, capture the screen in PARALLEL, then inject it via
  `GeminiLiveClient.pushFirstTurn(images, text)` (sends now if connected, else on
  setupComplete). Blocking the session on capture made wake mode "not respond".

## Wake word (always-listening)
- `SpeechRecognizer` with `EXTRA_LANGUAGE=ar-AE`. **Do NOT set `EXTRA_PREFER_OFFLINE=true`** —
  if the Arabic offline pack isn't installed, recognition fails every time and wake stops
  working entirely.
- Wake word → open a **background** Gemini session (voice only, no full-screen orb). The
  full-screen orb (LiveActivity / side-key AssistActivity) is a separate, deliberate mode.
- **Mic arbitration**: register `AudioManager.AudioRecordingCallback`; when another app
  records (WhatsApp/call) and we're not in our own session (`working==false`) and not
  listening, release the recognizer (`micBusy=true`), and resume when it stops. Otherwise the
  always-on recognizer blocks other apps' mic.
- Background activity launch (from the wake service) needs "Display over other apps"
  (SYSTEM_ALERT_WINDOW), prompted when enabling wake mode.

## Emirati (Al-Ain Bedouin) dialect for Gemini
- The OLD ElevenLabs pipeline wrote normal `ق` then post-replaced `ق→گ` in TTS. **Gemini has
  no such step** — instruct the MODEL to pronounce+write qaf as `گ` (hard "g", like "go"):
  گلت، گهوة، أگدر، رگم. Never `غ` (wrong meaning), never plain qaf.
- Other features: jim→ي (ريّال، دياي), feminine kaf→چ (أساعدچ), dad→ظ, tafkheem (heavy Bedouin
  articulation), initial kasra (كِلها، مِثل، نِبا). Addressee switch: elder/young × f/m.
- Gemini native audio follows dialect + tone instructions well (sighs, pauses, warmth) — lean
  on natural-language instructions, not text hacks.
- Forbidden non-Emirati words: طوالي (Sudanese)→أحينه/الحين; عايز→أبا/ودّي; كده→جذي.

## Reminders / time
- Inject the CURRENT local time + timezone (Asia/Dubai, UTC+4) into the system prompt each
  session, plus Arabic time-fraction rules (وربع=:15, والنص=:30, وثلث=:20, إلا ربع=:45 prev
  hour) — otherwise the model guesses AM/PM and "hours remaining" wrong.
- `set_reminder` takes `hour`+`minute` and sets the alarm silently via
  `AlarmClock.ACTION_SET_ALARM` + `EXTRA_SKIP_UI=true` (fully hands-free). Without a time it
  only opens the clock and reads back "set the time yourself" (looks like a refusal).

## Device control (Commands.kt)
- Tool-use loop: `open_app`, `youtube`, `play_music` (scrape first YouTube videoId for
  autoplay), `open_maps`, `navigate`, `call` (contact lookup + CALL_PHONE), `whatsapp`,
  `set_reminder`, `web_search`, `send_email`, `email_document` (DocxBuilder + FileProvider).
- `startActivity` from a Service needs `FLAG_ACTIVITY_NEW_TASK` (`fire()` adds it). Android 11+
  package visibility: `<queries>` needs a launcher-intent entry to see/launch other apps.
- `geminiTools()` converts Anthropic input_schema → Gemini functionDeclarations (types
  UPPERCASE: OBJECT/STRING); properties become STRING, so parse ints from strings.

## User context
- The user is an Emirati woman; her end-users include elderly and disabled/blind people.
  Keep everything hands-free, patient, spoken, and in authentic Al-Ain dialect.
- All API keys she pasted in chat are exposed — remind her to rotate them; never commit keys.
