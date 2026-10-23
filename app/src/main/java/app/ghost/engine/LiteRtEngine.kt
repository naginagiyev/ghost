package app.ghost.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import app.ghost.BuildConfig
import app.ghost.Config
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
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
    private val prepareRequests = AtomicLong(0)
    private var engine: Engine? = null
    private var modelFile: File? = null
    private var candidates: List<String> = emptyList()
    private var backendName = ""
    private var loadMs = 0L
    private var devicePrinted = false
    @Volatile private var conversation: Conversation? = null
    private var liveChatId: Long? = null
    private var liveFresh = false
    private var liveTurns = -1
    private var warmBuild = false
    private var warmAnswer = false
    @Volatile private var stopRequested = false
    @Volatile private var maxNewTokens = Config.MAX_NEW_TOKENS

    init {
        scope.launch {
            ExperimentalFlags.enableBenchmark = true
            Engine.setNativeMinLogSeverity(if (BuildConfig.DEBUG) LogSeverity.WARNING else LogSeverity.ERROR)
        }
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

    override fun prepare(chatId: Long?, history: List<Turn>) {
        val request = prepareRequests.incrementAndGet()
        scope.launch {
            mutex.withLock {
                if (request != prepareRequests.get()) return@withLock
                val loaded = engine ?: return@withLock
                try {
                    ensureConversation(loaded, chatId, history, Config.PREPARE_PROMPT_TOKENS)
                } catch (e: Exception) {
                    closeConversation()
                    if (BuildConfig.DEBUG) Log.d(TAG, "prepare failed: ${e.message}")
                }
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
                val live = ensureConversation(loaded, request.chatId, request.history, ContextBuilder.estimate(request.prompt))
                val start = SystemClock.elapsedRealtime()
                var firstAt = 0L
                var chunks = 0
                try {
                    live.sendMessageAsync(contentsOf(request.prompt)).collect { message ->
                        val text = textOf(message)
                        if (text.isNotEmpty()) {
                            if (firstAt == 0L) firstAt = SystemClock.elapsedRealtime()
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
                val tokens = bench?.lastDecodeTokenCount?.takeIf { it > 0 } ?: chunks
                val ttft = if (firstAt > 0) firstAt - start else end - start
                if (BuildConfig.DEBUG && bench != null) {
                    Log.d(TAG, "turn prefill=${bench.lastPrefillTokenCount} tok, first token after $ttft ms, image=${request.prompt.imagePath != null}")
                }
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
                    if (warmAnswer && tokens >= MIN_DECODE_SAMPLE_TOKENS) {
                        modelFile?.let { backends.recordDecode(it, backendName, stats.decodeTokensPerSecond) }
                    }
                    warmAnswer = true
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

    override suspend fun title(userText: String, answer: String): String? = withContext(dispatcher) {
        mutex.withLock {
            val loaded = engine ?: return@withLock null
            val config = ConversationConfig(
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0),
                maxOutputToken = Config.TITLE_TOKENS,
                thinkingConfig = ThinkingConfig(enableThinking = false),
            )
            val prompt = "Write a short title of 2 to 6 words for this conversation. " +
                "Reply with the title only, no quotes and no full stop.\n\n" +
                "User: ${userText.take(TITLE_SOURCE_CHARS)}\nAssistant: ${answer.take(TITLE_SOURCE_CHARS)}"
            try {
                val temporary = try {
                    loaded.createConversation(config)
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "second conversation refused, closing live one: ${e.message}")
                    closeConversation()
                    loaded.createConversation(config)
                }
                temporary.use { cleanTitle(textOf(it.sendMessage(prompt))) }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.d(TAG, "title failed: ${e.message}")
                null
            }
        }
    }

    override suspend fun stats(): List<String> = withContext(Dispatchers.IO) {
        val memory = memoryInfo()
        val file = modelStore.find()
        val pss = Debug.getPss() shr 10
        val tokens = runCatching { conversation?.getTokenCount() }.getOrNull()
        listOf(
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
        if (BuildConfig.DEBUG) Log.d(TAG, "model check $check, free ${memoryInfo().availMem shr 20} MB")
        backends.consumeCrash(file)?.let { say(Tag.WARN, "last load on $it crashed, using the other backend") }
        candidates = listOf(Backends.CPU, Backends.GPU)
            .filter { check.textBackends.isEmpty() || it in check.textBackends }
            .filter { !backends.isBad(it) }
        if (candidates.isEmpty()) {
            fail("no usable backend", "update the app or the phone system and try again")
            return
        }
        if (!openWithFallback(file, backends.choose(file, candidates))) {
            state.value = EngineState.Failed("load failed")
            return
        }
        modelFile = file
        state.value = EngineState.Ready(backendName)
    }

    private suspend fun openWithFallback(file: File, name: String): Boolean {
        if (open(file, name)) return true
        if (name == Backends.GPU && Backends.CPU in candidates) {
            backends.markBad(Backends.GPU)
            backends.store(file, Backends.CPU)
            candidates = listOf(Backends.CPU)
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
            warmBuild = false
            warmAnswer = false
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

    private suspend fun ensureConversation(loaded: Engine, chatId: Long?, history: List<Turn>, promptTokens: Int): Conversation {
        val live = conversation
        val sameChat = liveChatId == chatId || (liveFresh && history.isEmpty())
        if (live != null && sameChat && liveTurns == history.size) {
            val used = runCatching { live.getTokenCount() }.getOrDefault(Int.MAX_VALUE)
            if (used + promptTokens + maxNewTokens <= Config.CONTEXT_TOKENS) {
                liveChatId = chatId
                liveFresh = chatId == null
                return live
            }
        }
        closeConversation()
        val built = ContextBuilder.build(history, promptTokens, maxNewTokens = maxNewTokens)
        val restoring = built.history.isNotEmpty()
        if (restoring) say(Tag.PENDING, "restoring context (${built.history.size} messages)", RESTORE_KEY)
        if (built.dropped > 0) say(Tag.WARN, "context full, left out ${built.dropped} older messages")
        val start = SystemClock.elapsedRealtime()
        val config = ConversationConfig(
            systemInstruction = Contents.of(Config.systemPrompt()),
            initialMessages = built.history.map(::messageOf),
            samplerConfig = samplerConfig(),
            prefillPrefaceOnInit = true,
            maxOutputToken = maxNewTokens,
            thinkingConfig = ThinkingConfig(enableThinking = false),
        )
        val created = loaded.createConversation(config)
        val seconds = (SystemClock.elapsedRealtime() - start) / 1000.0
        val prefilled = runCatching { created.getTokenCount() }.getOrDefault(0)
        if (warmBuild && prefilled >= MIN_PREFILL_SAMPLE_TOKENS) {
            modelFile?.let { backends.recordPrefill(it, backendName, prefilled / seconds.coerceAtLeast(0.001)) }
        }
        warmBuild = true
        if (BuildConfig.DEBUG) Log.d(TAG, "conversation built with ${built.history.size} messages, $prefilled tokens in %.2fs".format(seconds))
        if (restoring) say(Tag.OK, "context restored in %.1fs".format(seconds), RESTORE_KEY)
        conversation = created
        liveChatId = chatId
        liveFresh = chatId == null
        liveTurns = history.size
        return created
    }

    private fun cleanTitle(raw: String): String? = raw.lineSequence()
        .map { it.trim().removePrefix("Title:").trim().trim('"', '\'', '*', '#', '.', ' ') }
        .firstOrNull { it.isNotEmpty() }
        ?.take(Config.TITLE_MAX_CHARS)

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
        liveFresh = false
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
        say(Tag.PLAIN, fix)
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

    private fun thermalStatus(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return PowerManager.THERMAL_STATUS_NONE
        return context.getSystemService(PowerManager::class.java).currentThermalStatus
    }

    private fun thermalWord(): String = when (thermalStatus()) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        else -> "shutdown"
    }

    private companion object {
        const val TAG = "GHOST"
        const val RESTORE_KEY = "restore"
        const val TITLE_SOURCE_CHARS = 600
        const val MIN_PREFILL_SAMPLE_TOKENS = 128
        const val MIN_DECODE_SAMPLE_TOKENS = 32
    }
}
