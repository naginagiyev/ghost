# Notes

Answers to plan Section 13, checked on 2026-10-22 against the official docs, the Gemma 4 model card, the Hugging Face model repo, and the public classes of the `litertlm-android` 0.18.1 AAR (read with `javap`, because the published sources jar is empty).

## 1. Library, version, minSdk

- Maven: `com.google.ai.edge.litertlm:litertlm-android:0.18.1` on Google Maven (latest stable, released 2026-10-20).
- Pulls in `gson 2.14.0`, `kotlin-reflect 2.4.0`, `kotlinx-coroutines-android 1.11.0`. Kotlin metadata is 2.4, so the app uses Kotlin 2.4.x.
- Library manifest says `minSdkVersion 24`. The app uses 26 (plan floor).
- Native code: a single `liblitertlm_jni.so` (22 MB) for `arm64-v8a` (also `x86_64`, removed by the ABI filter). GPU accelerators are inside this library.

## 2. Engine configuration

`EngineConfig(modelPath, backend, visionBackend, audioBackend, maxNumTokens, maxNumImages, cacheDir, activationDataType)`

- `backend`: `Backend.CPU(threadCount)`, `Backend.GPU()`, `Backend.NPU(nativeLibraryDir)`.
- `visionBackend`: separate backend for the vision encoder. Vision and audio parts load on demand.
- `maxNumTokens`: context size (used for `Config.CONTEXT_TOKENS`).
- `cacheDir`: writable directory for compiled artifacts. Set to `context.cacheDir`.
- `engine.initialize()` is slow (docs: up to ~10 s). Run it off the main thread.
- `Engine.setNativeMinLogSeverity(LogSeverity)` controls native logs.
- `ModelInfo.from(path)` reads the model file header: `supportedBackends(Modality)`, `inputModalities()`, `maxContextTokens()`, `maxVisionTokenBudget()`. Used to validate the file and check backends before init.
- GPU needs these inside `<application>` in the manifest:
  `<uses-native-library android:name="libvndksupport.so" android:required="false"/>` and
  `<uses-native-library android:name="libOpenCL.so" android:required="false"/>`.

## 3. Conversation API

- `engine.createConversation(ConversationConfig(systemInstruction, initialMessages, tools, samplerConfig, automaticToolCalling, channels, extraContext, loraConfig, prefillPrefaceOnInit, maxOutputToken, thinkingConfig, enableResponseFormat, enableSpeculativeDecoding, chatTemplate))`.
- Stream: `conversation.sendMessageAsync(contents): Flow<Message>` (also a callback form).
- Cancel: `conversation.cancelProcess()` stops native generation. Confirmed: the stream flow then ends with a `CancellationException` within about 1 s. The app treats it as a stop when it asked for one.
- Each `Message` in the stream holds only the new text (a delta), not the full answer so far.
- After a stop the conversation is closed and rebuilt from the database on the next message.
- Sampling: `SamplerConfig(topK, topP, temperature, seed)`.
- Initial history: `initialMessages = listOf(Message.user(...), Message.model(...))`. This is how a chat is restored after reload.
- `maxOutputToken` per conversation is used for `Config.MAX_NEW_TOKENS`.
- The KV cache lives inside the `Conversation`. Keeping one conversation per chat reuses it between turns.
- The app keeps at most one conversation alive and closes the old one before creating a new one.

## 4. Images

- `Content.ImageFile(absolutePath)` or `Content.ImageBytes(bytes)`, combined with text in `Contents.of(...)`.
- Gemma 4 image token budgets: 70, 140, 280, 560, 1120 (model card). LiteRT-LM exposes `ExperimentalFlags.visualTokenBudget`. Low budgets suit captioning, high budgets suit OCR.
- Measured on this phone: about 270 tokens per image with the default budget. `Config.IMAGE_TOKEN_COST = 280`.
- `EngineConfig.visionBackend` is separate from the text backend. GPU vision works on Adreno 650 and is faster and lighter than CPU vision here. The vision encoder loads on the first image (about +650 MB PSS) and stays loaded until unload.

## 5. Token counts

- `conversation.getTokenCount()` returns the exact number of tokens in the conversation so far.
- `conversation.getBenchmarkInfo()` returns init time, time to first token, last prefill and decode token counts, and prefill and decode tokens/sec. Measured: `timeToFirstTokenInSecond` keeps the value of the conversation's first turn and does not change per turn, so the app measures time to first token with its own clock. Works with `ExperimentalFlags.enableBenchmark = true` (`@ExperimentalApi`), set before the engine is created.
- No standalone tokenizer API. `ContextBuilder` uses `ceil(chars / 3.5)` for history that is not yet in the KV cache, and the status line uses the exact count from the conversation.

## 6. Thinking mode

- Chat template: `<|think|>` is added at the start of the system turn only when `enable_thinking` is true. Model card: remove the token to disable thinking. E2B and E4B do not emit an empty thought block when thinking is off.
- LiteRT-LM: `ConversationConfig(thinkingConfig = ThinkingConfig(enableThinking, thinkingTokenBudget))`. Thoughts come on the `"thought"` channel in `Message.channels`, separate from the main text.
- The app passes `ThinkingConfig(enableThinking = false)` and only shows `Message.contents` text, so any thought channel text is hidden.

## 7. Backends on this phone

- Poco F3, Snapdragon 870 (SM8250), Adreno 650 with `libOpenCL.so` in `/vendor/lib64`. CPU and GPU are candidates.
- NPU: not available. LiteRT-LM NPU models are compiled per chip (Qualcomm QCS8275, Google Tensor G5/G6). There is no build for SM8250. Out of scope for v1.
- Confirmed on the phone: the model header lists CPU and GPU for text, vision is present, max context 32003. GPU loads through OpenCL (Loaded OpenCL library with dlopen). NPU registry reports not found.
- GPU is faster here: prefill 459 vs 147 tok/s, decode 19.6 vs 14.9 tok/s. Numbers in `BASELINE.md`.

## 8. Sampling

Model card: `temperature=1.0`, `top_p=0.95`, `top_k=64` for all use cases. Same as the plan.

## 9. Model file

- Repo: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm (no login needed).
- File: `gemma-4-E2B-it.litertlm`, 2,588,147,712 bytes, SHA-256 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`.
- License: Apache-2.0.
- Other files in the repo are not for this app: `-web` (text only, for browsers), `_Qualcomm_QCS8275`, `_Google_Tensor_G5/G6`, `_intel_*` (NPU builds).
- Model supports up to 128K context. The app uses 4096 by default to save memory.

## 10. R8 and packaging

- The AAR has no consumer ProGuard rules, and the docs list none. Tested in Phase 5: the release build crashed (`NoSuchMethodError: SamplerConfig.getTopK()` called from JNI). The app keeps the whole package: `-keep class com.google.ai.edge.litertlm.** { *; }` in `app/proguard-rules.pro`. This is a difference from the plan, which said to add rules only if the docs list them.
- Native libraries: default AGP packaging (uncompressed, page-aligned). No extra options needed.

## Differences from the plan

- Braille spinner glyphs (U+2800 block) are missing from JetBrains Mono, so the spinner uses quadrant blocks `▘ ▝ ▗ ▖` (all in the font).
- Font: the `NL` (no ligatures) variant of JetBrains Mono, so `->`, `!=` and similar stay literal like in a terminal.

## Changes in Ghost (after the first version)

- Renamed TermChat to Ghost, package `app.ghost`. Android treats it as a new app, so the model was copied in again.
- Restore on open: `ConversationConfig(prefillPrefaceOnInit = true)` makes `createConversation` read the system prompt and all `initialMessages` right away. Confirmed on the phone: building a conversation with 16 messages (795 tokens) took 4.45 s, and the next turn only read the new 13 to 20 tokens. The app now builds the conversation when a chat is opened, when the model becomes ready, and after a stop, not when the first message is sent.
- Two conversations can be alive on one engine at the same time. Titles are written by a short second conversation (greedy, at most 20 tokens) next to the live chat, so the chat's KV cache is kept.
- Background calibration does not work on MIUI. A test that loaded the second backend after leaving the app was killed by `MiuiMemoryService` about 40 s later, while the process held 2.5 GB. So calibration is passive: the app uses GPU first, records real prefill speed from conversation builds (at least 128 tokens) and real decode speed from answers (at least 32 tokens), skipping the first of each after a load as warm-up. After 2 prefill and 3 decode samples on one backend, the next natural load uses the other one. When both are measured, the backend with the shorter typical turn (300 prompt tokens plus 200 answer tokens) is stored. On the Poco F3 it measured GPU 299 / 16.8 tok/s and CPU 140 / 14.3 tok/s and chose GPU.
- The choice and "bad backend" flags are keyed by model size, app version and Android build fingerprint, so an app or system update measures again.
- Low free RAM is no longer shown to the user. It is only logged in debug builds.
- `/bench`, `/img` and `/calibrate` were removed. The answer footer no longer shows tokens, speed and time; only `interrupted` or `error` is shown when it applies.
- Camera photos use `ActivityResultContracts.TakePicture` with a private `FileProvider` path in the cache folder. The app does not need the CAMERA permission because the system camera app takes the photo.
