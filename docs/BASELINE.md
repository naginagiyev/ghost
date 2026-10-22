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
