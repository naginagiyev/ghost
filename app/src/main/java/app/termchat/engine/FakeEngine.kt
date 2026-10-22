package app.termchat.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

class FakeEngine : LlmEngine {
    override val state = MutableStateFlow<EngineState>(EngineState.Unloaded)
    override val lines = MutableSharedFlow<SysLine>(extraBufferCapacity = 64)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var stopRequested = false

    override fun load() {
        if (state.value != EngineState.Unloaded) return
        state.value = EngineState.Loading
        scope.launch {
            lines.emit(SysLine(Tag.OK, "device fake engine"))
            lines.emit(SysLine(Tag.PENDING, "loading fake model", key = "load"))
            delay(1500)
            lines.emit(SysLine(Tag.OK, "backend fake, loaded in 1.5s", key = "load"))
            lines.emit(SysLine(Tag.INFO, "ready. type a message or /help"))
            state.value = EngineState.Ready("fake")
        }
    }

    override fun unload() {
        state.value = EngineState.Unloaded
        lines.tryEmit(SysLine(Tag.INFO, "model unloaded"))
    }

    override fun stop() {
        stopRequested = true
    }

    override fun generate(request: GenRequest): Flow<GenEvent> = flow {
        stopRequested = false
        state.value = EngineState.Generating("fake")
        val start = System.currentTimeMillis()
        delay(600)
        val ttft = System.currentTimeMillis() - start
        val words = CANNED.split(Regex("(?<=\\s)"))
        var count = 0
        try {
            for (word in words) {
                if (stopRequested) break
                emit(GenEvent.Chunk(word))
                count++
                delay(50)
            }
        } finally {
            state.value = EngineState.Ready("fake")
        }
        val total = System.currentTimeMillis() - start
        val decodeSeconds = (total - ttft).coerceAtLeast(1) / 1000.0
        val stats = GenStats(count, ttft, total, 0.0, count / decodeSeconds, count * 2)
        emit(GenEvent.Done(stats, interrupted = stopRequested))
    }

    override fun calibrate() {
        lines.tryEmit(SysLine(Tag.INFO, "fake engine has no backends"))
    }

    override fun bench() {
        lines.tryEmit(SysLine(Tag.INFO, "fake engine has no benchmark"))
    }

    override suspend fun stats(): List<String> = listOf("engine fake")

    override fun setThrottled(throttled: Boolean) {}

    private companion object {
        const val CANNED = """Short answer: air molecules scatter short (**blue**) wavelengths of sunlight far more than long (*red*) ones. This is called `Rayleigh scattering`.

## Why it works

1. Sunlight contains every visible color.
2. Scattering strength grows with `1/λ⁴`, so blue scatters about 5x more than red.
3. Your eyes are more sensitive to blue than violet.

- At sunset light travels through more air.
- Most blue is scattered away, so you see **orange and red**.

> The sky is not blue because of the ocean. That is a common myth.

```python
def scatter(wavelength_nm):
    return 1 / wavelength_nm ** 4

print(scatter(450) / scatter(700))
```

| Color | Wavelength |
|-------|------------|
| blue  | 450 nm     |
| red   | 700 nm     |

That is the whole story."""
    }
}
