# TermChat

A private chat app for Android. It runs Gemma 4 E2B fully on the phone with LiteRT-LM and shows the chat as a dark terminal session. Text and image input, multiple saved conversations, no internet permission.

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

The release build uses R8 and is signed with the debug key. For development use `assembleDebug` and `app-debug.apk`.

## Import the model

1. Copy `gemma-4-E2B-it.litertlm` to the phone, for example into `Download`.
2. Open TermChat, type `/import` and pick the file.
3. The app copies it into its private storage, checks the SHA-256 and loads it. The first load also measures CPU and GPU and keeps the faster one.

After the import the copy in `Download` can be deleted.

Faster for development (debug build only):

```
adb push gemma-4-E2B-it.litertlm /data/local/tmp/
adb shell run-as app.termchat mkdir -p files/models
adb shell run-as app.termchat cp /data/local/tmp/gemma-4-E2B-it.litertlm files/models/
```

## Use

Type a message and tap `⏎`. Enter adds a new line. `+` attaches one image, `■` stops an answer. Tap the top status line to see all chats. Long-press an answer to copy it.

| Command | Action |
|---|---|
| `/new` | start a new conversation |
| `/chats` | open the conversation list (long-press a chat to delete it) |
| `/img` | attach an image |
| `/import` | import the model file |
| `/stats` | device, model, memory and last answer stats |
| `/bench` | run the fixed benchmark (also logged under tag `BENCH`) |
| `/calibrate` | measure CPU and GPU again and keep the faster one |
| `/unload`, `/reload` | unload or reload the model |
| `/delete` | delete the current conversation (asks `rm? [y/N]`) |
| `/help` | list commands |

The model loads when the app opens and unloads 30 seconds after the app goes to the background.

## Debug helpers

Debug builds accept a prompt (and an optional image path readable by the app) from adb:

```
adb shell am start -n app.termchat/.MainActivity --es prompt "why is the sky blue"
```

## Docs

- `plan.md`: the build plan
- `docs/NOTES.md`: LiteRT-LM and Gemma 4 findings, and where the app differs from the plan
- `docs/BASELINE.md`: measured performance and memory on a Poco F3
- `docs/JetBrainsMono-OFL.txt`: font license
