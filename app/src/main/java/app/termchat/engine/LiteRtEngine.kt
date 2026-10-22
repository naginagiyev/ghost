package app.termchat.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import app.termchat.BuildConfig
import app.termchat.Config
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.ceil

@OptIn(ExperimentalApi::class)
class LiteRtEngine(
    private val context: Context,
    private val modelStore: ModelStore,
    private val backends: Backends,
) : LlmEngine {
    override val state = MutableStateFlow<EngineState>(EngineState.Unloaded)
    override val lines = MutableSharedFlow<SysLine>(extraBufferCapacity = 64)
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "llm-engine") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var modelFile: File? = null
    private var backendName = ""
    private var loadMs = 0L
    private var devicePrinted = false
    @Volatile private var conversation: Conversation? = null
    private var liveChatId: Long? = null
    private var liveTurns = -1
    private var restoring = false
    @Volatile private var stopRequested = false
    @Volatile private var maxNewTokens = Config.MAX_NEW_TOKENS

    init {
        ExperimentalFlags.enableBenchmark = true
        Engine.setNativeMinLogSeverity(if (BuildConfig.DEBUG) LogSeverity.WARNING else LogSeverity.ERROR)
    }

    override fun load() {
        scope.launch { mutex.withLock { if (engine == null) loadLocked() } }
    }

    override fun unload() {
        scope.launch {
            mutex.withLock {
                val wasLoaded = engine != null
                closeEngine()
                state.value = EngineState.Unloaded
                if (wasLoaded) say(Tag.DIM, "model unloaded")
            }
        }
    }

    override fun stop() {
        stopRequested = true
        runCatching { conversation?.cancelProcess() }
    }

    override fun calibrate() {
        scope.launch {
            mutex.withLock {
                closeEngine()
                modelStore.find()?.let { backends.reset(it) }
                loadLocked()
            }
        }
    }

    override fun setThrottled(throttled: Boolean) {
        val limit = if (throttled) Config.THROTTLED_MAX_NEW_TOKENS else Config.MAX_NEW_TOKENS
        if (limit == maxNewTokens) return
        maxNewTokens = limit
        scope.launch {
            mutex.withLock { closeConversation() }
            if (throttled) {
                say(Tag.WARN, "thermal throttling, answers capped at $limit tokens")
            } else {
                say(Tag.OK, "temperature normal, answer cap back to $limit tokens")
            }
        }
    }

    override fun generate(request: GenRequest): Flow<GenEvent> = flow {
        mutex.withLock {
            val loaded = engine ?: throw IllegalStateException("model not loaded")
            stopRequested = false
            state.value = EngineState.Generating(backendName)
            try {
                val live = prepareConversation(loaded, request)
                val start = SystemClock.elapsedRealtime()
                var firstAt = 0L
                var chunks = 0
                try {
                    live.sendMessageAsync(contentsOf(request.prompt)).collect { message ->
                        val text = textOf(message)
                        if (text.isNotEmpty()) {
                            if (firstAt == 0L) {
                                firstAt = SystemClock.elapsedRealtime()
                                if (restoring) {
                                    restoring = false
                                    say(Tag.OK, "context restored in %.1fs".format((firstAt - start) / 1000.0), RESTORE_KEY)
                                }
                            }
                            chunks++
                            emit(GenEvent.Chunk(text))
                        }
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "generation ended by ${e.javaClass.simpleName}")
                    if (e is CancellationException && !currentCoroutineContext().isActive) {
                        runCatching { live.cancelProcess() }
                        closeConversation()
                        throw e
                    }
                    if (!stopRequested) throw e
                }
                val end = SystemClock.elapsedRealtime()
                val bench = runCatching { live.getBenchmarkInfo() }.getOrNull()
                if (BuildConfig.DEBUG && bench != null) {
                    Log.d(TAG, "turn prefill=${bench.lastPrefillTokenCount} tok in %.2fs, image=${request.prompt.imagePath != null}".format(bench.timeToFirstTokenInSecond))
                }
                val tokens = bench?.lastDecodeTokenCount?.takeIf { it > 0 } ?: chunks
                val ttft = if (firstAt > 0) firstAt - start else end - start
                val decodeSeconds = (end - (if (firstAt > 0) firstAt else start)).coerceAtLeast(1) / 1000.0
                val stats = GenStats(
                    tokens = tokens,
                    ttftMs = ttft,
                    totalMs = end - start,
                    prefillTokensPerSecond = bench?.lastPrefillTokensPerSecond ?: 0.0,
                    decodeTokensPerSecond = bench?.lastDecodeTokensPerSecond?.takeIf { it > 0 } ?: (tokens / decodeSeconds),
                    contextTokens = runCatching { live.getTokenCount() }.getOrDefault(0),
                )
                val interrupted = stopRequested
                if (interrupted) {
                    closeConversation()
                } else {
                    liveTurns = request.history.size + 2
                }
                emit(GenEvent.Done(stats, interrupted))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                closeConversation()
                throw e
            } finally {
                if (engine != null) state.value = EngineState.Ready(backendName)
            }
        }
    }.flowOn(dispatcher)

    override fun bench() {
        scope.launch {
            mutex.withLock {
                val loaded = engine
                if (loaded == null) {
                    say(Tag.FAIL, "model not loaded, try /reload")
                    return@withLock
                }
                closeConversation()
                state.value = EngineState.Generating(backendName)
                try {
                    runBench(loaded)
                } catch (e: Exception) {
                    say(Tag.FAIL, "bench failed: ${e.message}")
                } finally {
                    state.value = EngineState.Ready(backendName)
                }
            }
        }
    }

    override suspend fun stats(): List<String> {
        val memory = memoryInfo()
        val file = modelStore.find()
        val pss = Debug.getPss() shr 10
        val tokens = runCatching { conversation?.getTokenCount() }.getOrNull()
        return listOf(
            "device     ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, ${Build.SUPPORTED_ABIS[0]}",
            "memory     ${memory.totalMem shr 20} MB total, ${memory.availMem shr 20} MB free, app pss $pss MB",
            "model      ${file?.name ?: "none"} ${file?.let { "(${it.length() shr 20} MB)" } ?: ""}",
            "engine     ${stateWord()}, backend ${backendName.ifEmpty { "-" }}, loaded in ${loadMs} ms",
            "kv cache   ${tokens ?: 0} tokens in live conversation",
            "thermal    ${thermalWord()}",
        )
    }

    private suspend fun loadLocked() {
        state.value = EngineState.Loading
        if (!devicePrinted) {
            devicePrinted = true
            val gb = ceil(memoryInfo().totalMem / (1024.0 * 1024 * 1024)).toInt()
            say(Tag.OK, "device $gb GB ${Build.SUPPORTED_ABIS[0]}")
        }
        val file = modelStore.find()
        if (file == null) {
            fail("no model found", "run /import and pick the gemma .litertlm file")
            return
        }
        val check = try {
            modelStore.inspect(file)
        } catch (e: Exception) {
            fail("cannot read ${file.name}: ${e.message}", "run /import to replace the model file")
            return
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "model check $check")
        val memory = memoryInfo()
        if (memory.availMem < file.length() + Config.MEMORY_MARGIN_MB.toLong() * 1024 * 1024) {
            say(Tag.WARN, "low memory (${memory.availMem shr 20} MB free), close other apps")
        }
        backends.consumeCrash(file)?.let { say(Tag.WARN, "last load on $it crashed, $it disabled, run /calibrate to retry") }
        val candidates = listOf(Backends.CPU, Backends.GPU)
            .filter { check.textBackends.isEmpty() || it in check.textBackends }
            .filter { !backends.isBad(it) }
        if (candidates.isEmpty()) {
            fail("no usable backend", "run /calibrate to retry all backends")
            return
        }
        val stored = backends.stored(file)?.takeIf { it in candidates }
        val ok = when {
            stored != null -> openWithFallback(file, stored, candidates)
            candidates.size == 1 -> open(file, candidates[0]).also { if (it) backends.store(file, candidates[0]) }
            else -> calibrateLocked(file, candidates)
        }
        if (!ok) {
            state.value = EngineState.Failed("load failed")
            return
        }
        modelFile = file
        state.value = EngineState.Ready(backendName)
        say(Tag.INFO, "ready. type a message or /help")
    }

    private suspend fun openWithFallback(file: File, name: String, candidates: List<String>): Boolean {
        if (open(file, name)) return true
        if (name == Backends.GPU && Backends.CPU in candidates) {
            backends.markBad(Backends.GPU)
            backends.store(file, Backends.CPU)
            say(Tag.WARN, "gpu failed, falling back to cpu")
            return open(file, Backends.CPU)
        }
        return false
    }

    private suspend fun open(file: File, name: String): Boolean {
        say(Tag.PENDING, "loading ${file.name} on $name", "load")
        val start = SystemClock.elapsedRealtime()
        backends.markLoading(name)
        return try {
            val config = EngineConfig(
                modelPath = file.absolutePath,
                backend = backends.create(name),
                visionBackend = backends.create(if (backends.isBad(Backends.GPU)) Backends.CPU else Config.VISION_BACKEND),
                maxNumTokens = Config.CONTEXT_TOKENS,
                cacheDir = context.cacheDir.absolutePath,
            )
            val created = Engine(config)
            created.initialize()
            engine = created
            backendName = name
            loadMs = SystemClock.elapsedRealtime() - start
            say(Tag.OK, "backend $name, loaded in %.1fs".format(loadMs / 1000.0), "load")
            true
        } catch (e: Exception) {
            say(Tag.FAIL, "$name load failed: ${e.message}", "load")
            false
        } finally {
            backends.clearLoading()
        }
    }

    private suspend fun calibrateLocked(file: File, candidates: List<String>): Boolean {
        say(Tag.PENDING, "calibrating backend (first run only)", "calibrate")
        var best: String? = null
        var bestSpeed = 0.0
        for (name in candidates) {
            closeEngine()
            if (!open(file, name)) {
                backends.markBad(name)
                continue
            }
            val speed = try {
                backends.markLoading(name)
                measureDecode(engine!!)
            } catch (e: Exception) {
                say(Tag.WARN, "$name calibration failed: ${e.message}")
                backends.markBad(name)
                0.0
            } finally {
                backends.clearLoading()
            }
            if (speed > 0) say(Tag.OK, "$name decode %.1f tok/s".format(speed))
            if (speed > bestSpeed) {
                best = name
                bestSpeed = speed
            }
        }
        val chosen = best
        if (chosen == null) {
            closeEngine()
            fail("calibration failed on every backend", "check free memory and run /calibrate")
            return false
        }
        backends.store(file, chosen)
        say(Tag.OK, "calibrating backend (first run only)", "calibrate")
        say(Tag.OK, "using $chosen (%.1f tok/s)".format(bestSpeed))
        if (backendName != chosen || engine == null) {
            closeEngine()
            return open(file, chosen)
        }
        return true
    }

    private suspend fun measureDecode(loaded: Engine): Double {
        val config = ConversationConfig(
            samplerConfig = samplerConfig(),
            maxOutputToken = Config.CALIBRATION_TOKENS,
            thinkingConfig = ThinkingConfig(enableThinking = false),
        )
        loaded.createConversation(config).use { calibration ->
            var firstAt = 0L
            var chunks = 0
            calibration.sendMessageAsync("Write a long story about a lighthouse keeper.").collect {
                if (firstAt == 0L) firstAt = SystemClock.elapsedRealtime()
                chunks++
            }
            val end = SystemClock.elapsedRealtime()
            val info = runCatching { calibration.getBenchmarkInfo() }.getOrNull()
            val measured = info?.lastDecodeTokensPerSecond ?: 0.0
            if (measured > 0) return measured
            return chunks / ((end - firstAt).coerceAtLeast(1) / 1000.0)
        }
    }

    private suspend fun runBench(loaded: Engine) {
        say(Tag.PENDING, "bench: 3 runs on $backendName", "bench")
        val pssBefore = Debug.getPss() shr 10
        val ttft = ArrayList<Double>()
        val prefill = ArrayList<Double>()
        val decode = ArrayList<Double>()
        var prefillTokens = 0
        var decodeTokens = 0
        repeat(BENCH_RUNS) { run ->
            val config = ConversationConfig(
                systemInstruction = Contents.of(Config.systemPrompt()),
                samplerConfig = samplerConfig(),
                maxOutputToken = BENCH_OUTPUT_TOKENS,
                thinkingConfig = ThinkingConfig(enableThinking = false),
            )
            loaded.createConversation(config).use { benchConversation ->
                val start = SystemClock.elapsedRealtime()
                var firstAt = 0L
                benchConversation.sendMessageAsync(BENCH_PROMPT).collect {
                    if (firstAt == 0L) firstAt = SystemClock.elapsedRealtime()
                }
                val info = benchConversation.getBenchmarkInfo()
                ttft.add(if (info.timeToFirstTokenInSecond > 0) info.timeToFirstTokenInSecond else (firstAt - start) / 1000.0)
                prefill.add(info.lastPrefillTokensPerSecond)
                decode.add(info.lastDecodeTokensPerSecond)
                prefillTokens = info.lastPrefillTokenCount
                decodeTokens = info.lastDecodeTokenCount
                Log.i(
                    BENCH_TAG,
                    "run ${run + 1} backend=$backendName ttft=%.3fs prefill=%.1f tok/s decode=%.1f tok/s".format(
                        ttft.last(),
                        prefill.last(),
                        decode.last(),
                    ),
                )
            }
        }
        val pssAfter = Debug.getPss() shr 10
        val results = listOf(
            "bench $backendName, median of $BENCH_RUNS runs",
            "ttft       %.2f s".format(median(ttft)),
            "prefill    %.1f tok/s ($prefillTokens tokens)".format(median(prefill)),
            "decode     %.1f tok/s ($decodeTokens tokens)".format(median(decode)),
            "pss        $pssBefore MB before, $pssAfter MB after",
            "thermal    ${thermalWord()}",
        )
        say(Tag.OK, "bench done", "bench")
        results.forEach {
            say(Tag.INFO, it)
            Log.i(BENCH_TAG, it)
        }
    }

    private suspend fun prepareConversation(loaded: Engine, request: GenRequest): Conversation {
        val live = conversation
        if (live != null && liveChatId == request.chatId && liveTurns == request.history.size) {
            val used = runCatching { live.getTokenCount() }.getOrDefault(Int.MAX_VALUE)
            if (used + ContextBuilder.estimate(request.prompt) + maxNewTokens <= Config.CONTEXT_TOKENS) return live
        }
        closeConversation()
        val built = ContextBuilder.build(request.history, request.prompt, maxNewTokens = maxNewTokens)
        restoring = built.history.isNotEmpty()
        if (restoring) say(Tag.PENDING, "restoring context (${built.history.size} messages)", RESTORE_KEY)
        if (built.dropped > 0) say(Tag.WARN, "context full, left out ${built.dropped} older messages")
        val config = ConversationConfig(
            systemInstruction = Contents.of(Config.systemPrompt()),
            initialMessages = built.history.map(::messageOf),
            samplerConfig = samplerConfig(),
            maxOutputToken = maxNewTokens,
            thinkingConfig = ThinkingConfig(enableThinking = false),
        )
        val created = loaded.createConversation(config)
        conversation = created
        liveChatId = request.chatId
        liveTurns = request.history.size
        return created
    }

    private fun samplerConfig() = SamplerConfig(
        topK = Config.TOP_K,
        topP = Config.TOP_P,
        temperature = Config.TEMPERATURE,
    )

    private fun contentsOf(turn: Turn): Contents {
        val parts = ArrayList<Content>()
        turn.imagePath?.let { parts.add(Content.ImageFile(it)) }
        if (turn.text.isNotEmpty()) parts.add(Content.Text(turn.text))
        return Contents.of(parts)
    }

    private fun messageOf(turn: Turn): Message =
        if (turn.role == Role.USER) Message.user(contentsOf(turn)) else Message.model(turn.text)

    private fun textOf(message: Message): String =
        message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    private fun closeConversation() {
        val old = conversation
        conversation = null
        liveChatId = null
        liveTurns = -1
        runCatching { old?.close() }
    }

    private fun closeEngine() {
        closeConversation()
        val old = engine
        engine = null
        backendName = ""
        runCatching { old?.close() }
    }

    private suspend fun fail(reason: String, fix: String) {
        say(Tag.FAIL, reason)
        say(Tag.INFO, fix)
        state.value = EngineState.Failed(reason)
    }

    private suspend fun say(tag: Tag, text: String, key: String? = null) {
        lines.emit(SysLine(tag, text, key))
    }

    private fun memoryInfo(): ActivityManager.MemoryInfo {
        val manager = context.getSystemService(ActivityManager::class.java)
        return ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
    }

    private fun stateWord(): String = when (state.value) {
        EngineState.Unloaded -> "unloaded"
        EngineState.Loading -> "loading"
        is EngineState.Ready -> "ready"
        is EngineState.Generating -> "generating"
        is EngineState.Failed -> "failed"
    }

    private fun thermalWord(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "unknown"
        return when (context.getSystemService(PowerManager::class.java).currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            else -> "shutdown"
        }
    }

    private fun median(values: List<Double>): Double = values.sorted()[values.size / 2]

    private companion object {
        const val TAG = "TERMCHAT"
        const val BENCH_TAG = "BENCH"
        const val RESTORE_KEY = "restore"
        const val BENCH_RUNS = 3
        const val BENCH_OUTPUT_TOKENS = 200
        const val BENCH_PROMPT = "You are helping me plan a small vegetable garden behind my house. The garden " +
            "bed is about three meters long and one meter wide, it gets direct sun for roughly six hours in the " +
            "morning, and the soil is heavy clay that stays wet after rain. I live in a temperate climate with " +
            "warm summers and cold winters. I want to grow tomatoes, lettuce, carrots, beans and some herbs like " +
            "basil and parsley. I have a limited budget and only a few hours each weekend. Please give me a " +
            "detailed step by step plan: how to improve the soil, how to arrange the plants in the bed, when to " +
            "plant each crop, how often to water, and which common mistakes to avoid during the first season."
    }
}
