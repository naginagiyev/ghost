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
