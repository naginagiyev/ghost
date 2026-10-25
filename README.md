# Ghost

A private chat app for Android. It runs Gemma 4 models (E2B by default) fully on the phone with LiteRT-LM and shows the chat as a dark terminal session. Text, gallery and camera images, documents (PDF, DOCX, Markdown, TXT), several models, multiple saved conversations, no internet permission.

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

## Import a model

1. Copy a `.litertlm` model (for example `gemma-4-E2B-it.litertlm`) to the phone, for example into `Download`.
2. Open Ghost, tap the model name at the top right, tap `+ import a .litertlm file` and pick the file.
3. The app copies it into its private storage, checks it and switches to it.

After the import the copy in `Download` can be deleted. Several models can be kept side by side.

Faster for development (debug build only):

```
adb push gemma-4-E2B-it.litertlm /data/local/tmp/
adb shell run-as app.ghost mkdir -p files/models
adb shell run-as app.ghost cp /data/local/tmp/gemma-4-E2B-it.litertlm files/models/
```

## Use

Type a message and tap `[ send ]`. Enter adds a new line. `+` opens `[ photo ]`, `[ camera ]` and `[ file ]` to attach one image or one document, `[ stop ]` ends an answer. Long-press an answer to copy it. Long-press your last message to edit it and get a new answer.

Everything works with taps, there are no slash commands:

- `≡ chats` (top left) opens the chat list. Tap a chat to open it, hold it for `[ pin ]`, `[ rename ]` and `[ delete ]`, and use `+ new` to start a new one.
- The model name (top right) opens `~/models`. Tap a model to switch to it, hold one to delete it, and tap `+ import a .litertlm file` to add one. The dot shows the state: green ready, amber loading or busy, red failed, gray unloaded.

New chats get a short title written by the model after the first answer. Answers show Markdown: headings, lists, bold and italic, code, tables (drawn as a grid, scroll sideways when wide) and LaTeX formulas (shown as plain symbols, for example `½mv²`).

The model loads when the app opens and unloads 30 seconds after the app goes to the background. Opening a chat restores its context right away, so the first message does not wait for it. CPU or GPU, the context size and speculative decoding are chosen automatically per model from measurements of normal use. Long chats keep short notes of important facts instead of forgetting old messages (see `docs/NOTES.md`).

Documents: PDF (with a text layer), DOCX, Markdown and TXT. The text is read on the phone and sent with your message; very long documents are cut to fit the context. Scanned PDFs have no text, so send a photo of the page instead.

## Debug helpers

Debug builds accept a prompt (and an optional `--es image` or `--es doc` path readable by the app) from adb. The prompt `/new` starts a new conversation:

```
adb shell am start -n app.ghost/.MainActivity --es prompt "why is the sky blue"
```

## Docs

- `plan.md`: the original build plan
- `docs/NOTES.md`: LiteRT-LM and Gemma 4 findings, and where the app differs from the plan
- `docs/BASELINE.md`: measured performance and memory on a Poco F3
- `docs/JetBrainsMono-OFL.txt`: font license
