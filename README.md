# Ghost

A private chat app for Android. It runs Gemma 4 E2B fully on the phone with LiteRT-LM and shows the chat as a dark terminal session. Text, gallery and camera images, multiple saved conversations, no internet permission.

## Requirements

- Android 8.0 (API 26) or newer, `arm64-v8a`, 8 GB RAM recommended
- JDK 17 or newer (Android Studio's bundled JBR works)
- Android SDK with platform 37
- The model file `gemma-4-E2B-it.litertlm` (2.59 GB, Apache-2.0) from https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm

## Build and install

```
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build uses R8 and is signed with the debug key. For development use `assembleDebug` and `app-debug.apk`. On MIUI the first install of a new package must be approved on the phone.

## Import the model

1. Copy `gemma-4-E2B-it.litertlm` to the phone, for example into `Download`.
2. Open Ghost, type `/import` and pick the file.
3. The app copies it into its private storage, checks the SHA-256 and loads it.

After the import the copy in `Download` can be deleted.

Faster for development (debug build only):

```
adb push gemma-4-E2B-it.litertlm /data/local/tmp/
adb shell run-as app.ghost mkdir -p files/models
adb shell run-as app.ghost cp /data/local/tmp/gemma-4-E2B-it.litertlm files/models/
```

## Use

Type a message and tap `[ send ]`. Enter adds a new line. `+` opens `[ gallery ]` and `[ camera ]` to attach one image, `[ stop ]` ends an answer. Long-press an answer to copy it.

Tap the top status line to see all chats. Hold a chat for `[ pin ]`, `[ rename ]` and `[ delete ]`. New chats get a short title written by the model after the first answer.

| Command | Action |
|---|---|
| `/new` | start a new conversation |
| `/chats` | open the conversation list |
| `/import` | import the model file |
| `/stats` | device, model, memory and last answer stats |
| `/unload`, `/reload` | unload or reload the model |
| `/delete` | delete the current conversation (asks with `[ delete ]` / `[ cancel ]`) |
| `/help` | list commands |

The model loads when the app opens and unloads 30 seconds after the app goes to the background. Opening a chat restores its context right away, so the first message does not wait for it. CPU or GPU is chosen automatically from the speed of normal use (see `docs/NOTES.md`).

## Debug helpers

Debug builds accept a prompt (and an optional image path readable by the app) from adb:

```
adb shell am start -n app.ghost/.MainActivity --es prompt "why is the sky blue"
```

## Docs

- `plan.md`: the original build plan
- `docs/NOTES.md`: LiteRT-LM and Gemma 4 findings, and where the app differs from the plan
- `docs/BASELINE.md`: measured performance and memory on a Poco F3
- `docs/JetBrainsMono-OFL.txt`: font license
