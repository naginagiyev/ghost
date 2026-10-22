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
