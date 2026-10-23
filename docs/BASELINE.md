# Baseline

## Device

| Item | Value |
|---|---|
| Phone | Xiaomi Poco F3 (`M2012K11AG`, `alioth_global`) |
| SoC | Qualcomm Snapdragon 870 (SM8250, `kona`), 8 cores, max 3.19 GHz |
| GPU | Adreno 650, OpenGL ES 3.2, OpenCL present |
| Android | 13 (API 33), MIUI `V816.0.5.0.TKHMIXM` |
| ABI | arm64-v8a |
| RAM | 7.7 GB total (`MemTotal 7701716 kB`), about 1.9 GB available at rest |
| Free storage | 62 GB of 225 GB |
| Screen | 1080x2400, 440 dpi |

## Laptop

| Item | Value |
|---|---|
| OS | Windows 11 |
| JDK | Android Studio JBR 25.0.3 |
| SDK | platforms 36 and 37, build-tools 36.0.0 |
| Gradle / AGP / Kotlin | 9.8.0 / 9.4.1 / 2.4.20 |

## Model

| Item | Value |
|---|---|
| File | `gemma-4-E2B-it.litertlm` |
| Size | 2,588,147,712 bytes (2.41 GiB) |
| SHA-256 | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` |

## Phase 1: fake engine UI

| Metric | Value |
|---|---|
| Cold start to UI, debug build (`am start -W` TotalTime) | 1332 ms |
| Cold start to UI, release build | 752 ms |
| UI publishes while streaming (fake, 20 tok/s) | 150 in 8.1 s (18.5/s) |
| Jank while streaming, release build (`gfxinfo`, 120 Hz) | 0.49% (1 of 203 frames), p90 8 ms |
| Jank while streaming, debug build | 17.2%, p90 13 ms (debug Compose is not optimized) |
| Rotation during streaming | no crash, stream continues |

## Phase 2: real engine, text (debug build, Gemma 4 E2B, 4096 context)

`/bench`: fixed 343-token prefill (system prompt plus a 120-word question), 200 decode tokens, median of 3 runs.

| Metric | GPU (Adreno 650) | CPU (4 threads default) |
|---|---|---|
| First load ever (no compile cache) | 10.2 s | 5.1 s |
| Later loads (cache in `cacheDir`) | 3.3 to 4.4 s | 0.6 s (file already in page cache) |
| Time to first token | 0.80 s | 2.40 s |
| Prefill | 459 tok/s | 147 tok/s |
| Decode | 19.6 tok/s | 14.9 tok/s |
| 64-token calibration decode | 12.8 to 14.2 tok/s | 11.0 to 12.9 tok/s |

Calibration picks GPU on this phone. The short calibration run reads lower than `/bench` because of warm-up, but the order is the same.

| Memory (`dumpsys meminfo`, TOTAL PSS) | Value |
|---|---|
| Empty app, no model, foreground / background | 137 MB / 91 MB |
| Model loaded on GPU, idle | 1.76 to 1.94 GB |
| Generating on GPU | 1.78 to 1.92 GB |
| After unload, foreground | 331 MB (native heap keeps 287 MB of freed pages, only 21 MB allocated) |
| After unload, background (grace period) | 137 MB |

Lifecycle checks (all passed on the phone):

- Cold start shows the UI in about 1 s while the model loads in the background.
- Home during generation: native generation stops, partial answer saved as `interrupted`, model unloaded after 30 s, PSS 1.91 GB to 137 MB. Returning reloads in 3.6 s.
- `■` stops generation within about 1 s, footer shows real partial stats.
- `/unload` then a message: `waiting for model`, reload, answer.
- Crash flag set by hand (`loading_in_progress=gpu`): next launch prints `[warn] last load on gpu crashed`, uses CPU. `/calibrate` restores GPU.
- MIUI compacts a backgrounded process within seconds (PSS drops before our unload) because the model is memory-mapped.

## Phase 3: persistence and context

- 3 chats survive `am force-stop` and relaunch with all messages (titles, counts, order by last update).
- Opening an old chat and sending a message replays it from the database: `context restored in 1.3s` (2 earlier messages).
- Long chat: 6 messages of about 3,000 characters each. At message 5 the rebuild left out the 4 oldest messages (`[warn] context full`), live context went from 2.7k to 2.1k tokens, and the chat kept answering.
- Gemma's tokenizer gives about 4.6 characters per token on plain English, so the `chars / 3.5` estimate is on the safe side.
- `/delete` and chat-list long-press both ask `rm "title"? [y/N]` and delete only on `y`.

## Phase 4: images (GPU text backend)

| Metric | GPU vision | CPU vision |
|---|---|---|
| Whole image turn (send to last token, short answer) | 7.6 to 8.2 s | 12.1 s |
| PSS peak during image turn | 2.59 GB | 2.93 GB |
| PSS after the turn (vision encoder stays loaded) | 2.55 GB | 2.93 GB |
| UI during the turn | one 1.4 s frame stall on first use | 56% janky frames (all cores busy) |

GPU vision is used (`Config.VISION_BACKEND = "gpu"`, CPU only when GPU is marked bad).

- Image token cost: the turn prefill was 477 to 486 tokens versus about 210 for the system prompt plus a short text question, so one image costs about 270 tokens. This matches the Gemma 4 default budget of 280. `IMAGE_TOKEN_COST` stays 280.
- The vision encoder resizes to its own token budget, so `IMAGE_MAX_SIDE` changes legibility, not token cost. At 512 px a 1600x1200 page with 44 pt text was read correctly (door code and list item). 512 is kept: files are 6 to 12 KB.
- Answers referenced the real content: shopping list page (code 7342, item 2 Bananas) and a drawn scene (red house, brown roof, yellow sun, green field).
- Replay after `/unload`: a follow-up text question about the picture restored the image from history (537 prefill tokens) and answered correctly.
- Real Photo Picker (`com.google.android.photopicker`) works with no storage permission.

## Phase 5: release build (R8, debuggable=false, signed with the debug key)

Section 9 budget, measured on the release build.

| Metric | Result | Target |
|---|---|---|
| Cold start to interactive UI (`am start -W`, 5 runs) | median 238 ms (215 to 401) | under 1 s: met |
| Model load, GPU (compile cache warm) | 3.1 to 3.7 s | record |
| Model load, GPU first ever / CPU | 10.2 s / 0.6 to 5.1 s | record |
| Time to first token, GPU (`/bench`, 343-token prefill) | 0.79 s | record |
| Prefill / decode, GPU | 463 / 19.0 tok/s | record |
| Prefill / decode, CPU (debug build, same engine code) | 147 / 14.9 tok/s, TTFT 2.40 s | record |
| Footer speed in real chats (tokens / total time) | 17 to 19 tok/s for 140-token answers | record |
| UI jank while streaming text, 120 Hz screen | 0.0 to 1.4% janky, p99 14 to 17 ms (3 runs) | under 5%: met |
| UI publish rate while streaming | about 12 to 19 per second (follows token rate, capped at 30) | at most 30: met |
| PSS: loaded idle / generating / with image | 1.89 GB / 1.91 GB / 2.53 to 2.59 GB | record |
| PSS after unload (background) / empty app | 137 MB / 91 MB | near baseline: met |
| Thermal, 24 `/bench` runs in 5 min | status `none` the whole time, decode stayed 19.0 to 19.1 tok/s | no crash: met |
| Battery | 80.6 s app CPU time in the 5-minute bench session | gap: this ROM's power profile reports 0 mAh for every app, and GPU time is not attributed |

Changes made in this phase because of measurements:

- Jank while streaming was 5.5 to 8.3% on the 120 Hz screen, because the model decodes on the same GPU that draws the UI. The app now asks for 60 Hz (`preferredRefreshRate`) while a message is in progress, which brought it to 0 to 1.4% and also saves power.
- Startup did about 100 ms of disk reads on the main thread (`filesDir`, SharedPreferences, LiteRT native library). These now happen lazily or on the engine thread. The only remaining StrictMode hit is MIUI's own font fallback code during the first frame.
- R8 broke generation: native code calls `SamplerConfig.getTopK()` by name through JNI, and the release build aborted with `JNI DETECTED ERROR ... mid == null`. Fixed with `-keep class com.google.ai.edge.litertlm.** { *; }`.
- Thermal listener added: at `THERMAL_STATUS_SEVERE` or worse, answers are capped at 256 tokens and a `[warn] thermal throttling` line is shown. Not triggered on this phone in 5 minutes of continuous benchmarking.
- Baseline Profile and ADPF were skipped: cold start is already 4x under budget, and decode speed did not drop under sustained load.

## Phase 6: privacy checklist and failure tests

Section 11 privacy checklist (all pass):

- Merged release manifest has no `INTERNET` permission (`aapt2 dump permissions`). The only permission is androidx's internal signature permission `app.termchat.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.
- `allowBackup=false` and `dataExtractionRules` (every domain excluded for cloud backup and device transfer) are in the merged manifest.
- Runtime libraries: AndroidX (Compose, activity, lifecycle, Room), Kotlin, LiteRT-LM, Gson, kotlin-reflect. No analytics, crash reporting or networking libraries.
- Release logs: a message with a unique word was sent and answered. The word appears nowhere in logcat from the app process (only in adbd's log of the test's own `input text` command).
- Model, database and images are under `/data/user/0/app.termchat/` (`drwx------`). Files are `-rw-------`.
- Deleting a conversation deletes its image file. Room creates `ON DELETE CASCADE` and turns on `PRAGMA foreign_keys`, so its messages are removed too.

Section 12 failures, tried on purpose (no crash in any case):

| Failure | Result |
|---|---|
| No model file | `[fail] no model found` and `run /import and pick the gemma .litertlm file`, red status dot |
| Random bytes named `.litertlm` | `[fail] cannot read corrupt.litertlm: Failed to open LiteRT-LM file`, `run /import to replace the model file` |
| Real model cut to 1 GB (interrupted copy) | same clear `[fail]` line |
| GPU crash during load (crash flag) | `[warn] last load on gpu crashed, gpu disabled`, CPU used (Phase 2) |
| Low free RAM | `[warn] low memory (N MB free), close other apps`, load still tried and succeeded |
| Process killed in background | relaunch reloads, all chats intact (Phase 3) |
| `/import` from Downloads | progress line, SHA-256 matched the official hash, old model replaced, reload on GPU in 10.6 s (compile cache rebuilt) |

Fixes from this phase:

- An image attached but then cleared (or replaced) before sending is now deleted from storage. Before, it stayed as an orphan file.
- The `y` and `N` in the chat-list delete confirm now have larger tap areas.
- Removed `FakeEngine` (unused since Phase 2) and the `room-ktx` dependency (Room 2.8 includes coroutine support in `room-runtime`).

Release APK size: 23.1 MB.
