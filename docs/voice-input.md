# Voice input

## Codex Live

The Live button next to dictation opens a voice conversation in the current Codex chat. The central orb follows actual input and output audio levels. Microphone mute, text input, voice/language settings, and end-call controls are available. A call started while the app is visible continues when switching apps or locking the screen. The Codex Live notification opens the call or ends voice without interrupting an existing Codex task. Losing audio focus, changing chats, removing the task, or destroying the activity ends capture; returning never restarts an ended call automatically.

`LiveAudioService` holds microphone/media-playback foreground service eligibility, audio focus, and a partial wake lock only for the current call. The activity keeps the existing WebRTC peer and receives engine events while hidden; rendering is paused. Capture is never started from the background. Native teardown releases resources on errors even when the web page is hidden. Pending task approvals still require the existing approval flow. Device battery management or process termination can end a call.

The app uses the bundled Codex app-server's experimental v3 WebRTC protocol and existing account. Audio is carried by WebRTC; native signaling and delegated work use the existing engine, task model, permissions, and approval handling. There is no API-key fallback or alternate model. Unsupported account access and connection errors remain visible.

Voices come from `thread/realtime/listVoices`: use its `v3` catalog when present, otherwise retain the older runtime's `v1` catalog. The app does not add names missing from the engine's response. The bundled runtime includes the expanded [voice catalog patch](../tools/runtime-patches/README.md). Confirmed Korean, American English, and German voices are grouped; unclassified voices remain under Other. Korean voice names are shown as 한울 and 햇살. This runtime does not expose a language catalog: Auto, Korean, and English select conversation instructions. Changing voice or language starts a fresh voice connection in the same Codex thread. Canonical transcript segments are stored once in the existing chat history, including final segments arriving after stop.

For work requests, use speech or the normal chat composer. In device testing, text entered inside Live produced spoken acknowledgements without starting the requested Codex task; spoken requests successfully delegated work. Project-file reads and writes through spoken instructions were also confirmed by the user.

`node tools/verify_live_browser.cjs` exercises a real local WebRTC peer with synthetic test audio and a fixture bridge at 320/390/1280px. It checks connection, settings/reconnection, mute, text, denied/cancelled permissions, and cleanup. This is not evidence of OpenAI account access or physical-device audio.

Device acceptance: install the test APK over the existing app with the original signing certificate; retain login and history. Verify login remains, Live connects, both speakers are audible, the orb follows both audio levels, Korean speech, mute, text, voice changes, interruption, delegated work/approval, and persisted transcripts after restart. During a call, switch to another app and then lock the screen; verify speech and spoken work results continue in both states. Return and confirm the same call and transcript remain. End using the notification and verify the microphone indicator, Live notification, and wake lock are released while an existing Codex task continues. Also check focus loss, task removal, network failure, and immediate cancellation during startup. Check Android logs for each failure. Do not publish until physical-device testing and user acceptance are complete.

## Dictation

In the main chat, the microphone starts **inline dictation**. The composer displays microphone permission/preparation, listening, and transcription states. It shows actual microphone level and interim text when supplied by the device service. Choose **Done** to stop recording and wait for final transcription, or **Cancel** to discard it. Final text goes into the draft and is never sent automatically.

The first use requests Android microphone permission. Backgrounding/locking the main activity cancels an active recording; returning does not automatically restart it. A permission dialog is allowed to complete without being treated as a recording interruption. Provider silence detection may finish recognition before Done is pressed. Startup and final-result waits have timeouts; the app does not impose its own listening-duration limit. Late results after cancellation cannot be inserted into a new session.

Floating chat currently retains Android’s separate recognition screen. Both paths use the same durable result receipts:

- If the draft is unchanged, insert at the original cursor or replace the selected range. If it changed, preserve the edit and append the recognized text.
- Results belong to the original conversation/project even if the user switches chats.
- Persist the native receipt before acknowledging it. A recovered receipt must not append twice after process recreation.
- Recognition blocks new phone automation actions. Cancelling or an error keeps the original draft intact.

Recognition uses Android `SpeechRecognizer` in the main composer and `ACTION_RECOGNIZE_SPEECH` for floating chat. This is not the ChatGPT app’s speech backend. The selected app language is requested for inline recognition; System uses the device locale. The service may send audio to its servers and may require internet access. Accuracy, supported languages, silence handling, and offline availability depend on that provider. Mobile Codex does not save an audio file or send audio to a developer-operated server. Dictation is separate from Codex Live.

## Verification

Automated checks cover language requests, result parsing, recording states, cancellation/late callbacks, scope-bound receipt recovery, preserving edits, and preventing automatic sends. Physical-device checks remain necessary for microphone permissions, Korean/English recognition, Samsung speech services, silence detection, network loss, rotation, screen lock, and floating chat return behavior.

References: [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer) · [RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent).
