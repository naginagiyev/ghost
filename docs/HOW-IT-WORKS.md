# How Ghost works

Ghost is a single-module Android app written in Kotlin and Jetpack Compose. It runs a Gemma 4 model with [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) on the phone's GPU (or CPU) and never talks to the network.

## Layout

```
app/src/main/java/app/ghost/
├── App.kt              creates the engine, repository and stores once per process
├── MainActivity.kt     hosts the Compose UI and the photo, camera and file pickers
├── ChatController.kt   ViewModel: sending, editing, documents, streaming, summaries
├── Config.kt           every tunable number and the system prompt
├── engine/             everything that touches the model
│   ├── LlmEngine.kt        interface, states and events
│   ├── LiteRtEngine.kt     the LiteRT-LM implementation (one thread, one mutex)
│   ├── ContextBuilder.kt   picks the history that fits the context (unit tested)
│   ├── Backends.kt         CPU or GPU, chosen from measured speeds; crash flag
│   ├── ContextSizer.kt     context size chosen from measured memory
│   ├── Speculation.kt      speculative decoding on or off per model
│   ├── ModelStore.kt       lists, imports, switches and deletes model files
│   └── ModelLifecycle.kt   loads in the foreground, unloads 30 s after leaving
├── data/               Room database, image and document files
└── ui/                 Compose screens, Markdown and LaTeX rendering
```

## The engine

- **One thread, one lock.** All model calls run on a single `llm-engine` thread behind a coroutine `Mutex`, so loading, generating and unloading can never overlap.
- **The model belongs to the process, not the screen.** Rotating or rebuilding the screen never reloads it. It loads when the app becomes visible and unloads 30 seconds after the app leaves the screen.
- **Warm-up.** Right after loading, a one-token reply compiles the GPU kernels, which makes the first real answer about twice as fast.
- **Context is restored when a chat opens**, not when you send. LiteRT-LM keeps the KV cache inside a `Conversation`; Ghost rebuilds it from the database (`prefillPrefaceOnInit`) so the next message only reads the new tokens.
- **Restore budget.** At most about 10 seconds of reading (measured prefill speed × 10 s) is restored. Messages are counted with the exact token counts the model reported when they were sent.
- **Summary notes.** When old messages no longer fit, the model writes up to 12 short fact lines about them ("The user's name is Sam."), which are added to the system prompt. Titles and notes run in the background, wait while you type, and restart if you start typing.
- **Retry.** A failed message is retried once with a fresh conversation.

## Self-tuning per model

Each decision is measured from normal use and stored per model file, app version and Android build:

| Decision | How it is made |
|---|---|
| CPU or GPU | GPU first. After 2 prefill and 3 decode samples on each, the backend with the shorter typical turn (300 prompt + 200 answer tokens) wins. |
| Context size | Starts at 4K. Memory is measured after the first answer, and the largest of 4K, 8K, 16K and 32K whose predicted memory stays under 45% of RAM is used next time. |
| Speculative decoding | 3 answers without it, then 3 with it on the next load; kept only if at least 10% faster. A GPU error turns it off. |

A flag is written to disk before the native engine starts and removed after it succeeds. If the process dies in between and Android reports a crash (`ApplicationExitInfo`), the next launch lowers the context size or avoids that backend.

## Documents and formatting

- PDF (pdfbox-android), DOCX (its XML is read directly), Markdown and TXT are turned into plain text on the phone and sent with the message. Long documents are cut to fit the context.
- Answers are rendered from Markdown: headings, lists, code, quotes, links, tables (drawn as a box grid) and LaTeX (converted to symbols, for example `\frac{1}{2}mv^2` → `½mv²`).

## Measured on a Poco F3 (Snapdragon 870, 8 GB)

| | Gemma 4 E2B | Gemma 4 E4B |
|---|---|---|
| Backend / context | GPU, 8K | GPU, 4K |
| Load (cached) | about 6 s | about 14 s |
| Prefill | about 300 tok/s | about 100 tok/s |
| Decode | 16 to 17 tok/s | 7 tok/s, 11 tok/s with speculative decoding |
| App memory (PSS) | about 2.8 GB | about 4.6 GB |

## Release builds

- R8 is on. LiteRT-LM calls Kotlin methods by name from native code, so `app/proguard-rules.pro` keeps the whole `com.google.ai.edge.litertlm` package.
- The app has no `INTERNET` permission and turns off cloud backup and device transfer for all data.
