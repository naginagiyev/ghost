package app.ghost.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
    private val contextSizer: ContextSizer,
    private val speculation: Speculation,
) : LlmEngine {
    override val state = MutableStateFlow<EngineState>(EngineState.Unloaded)
    override val lines = MutableSharedFlow<SysLine>(extraBufferCapacity = 64)
    override val model = MutableStateFlow<LoadedModel?>(null)
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "llm-engine") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private val prepareRequests = AtomicLong(0)
    private var engine: Engine? = null
    private var modelFile: File? = null
    private var candidates: List<String> = emptyList()
    private var backendName = ""
    private var speculative = false
    private var contextTokens = Config.CONTEXT_TOKENS
    private var loadMs = 0L
    private var devicePrinted = false
    @Volatile private var conversation: Conversation? = null
    @Volatile private var background: Conversation? = null
    @Volatile private var backgroundCancelled = false
    @Volatile private var backgroundTyping = false
    @Volatile private var lastTypingAt = 0L
    private var liveChatId: Long? = null
    private var liveFresh = false
    private var liveTurns = -1
    private var warmAnswer = false
    private var memoryRecorded = false
    @Volatile private var stopRequested = false
    @Volatile private var maxNewTokens = Config.MAX_NEW_TOKENS
    @Volatile private var restoreBudget = Config.CONTEXT_TOKENS / 2

    init {
        scope.launch {
            ExperimentalFlags.enableBenchmark = true
            Engine.setNativeMinLogSeverity(if (BuildConfig.DEBUG) LogSeverity.WARNING else LogSeverity.ERROR)
        }
    }

    override fun load() {
        scope.launch { mutex.withLock { if (engine == null) loadLocked() } }
    }

    override fun userTyping() {
        lastTypingAt = SystemClock.elapsedRealtime()
        backgroundTyping = true
        runCatching { background?.cancelProcess() }
    }

    override fun userWaiting() {
        lastTypingAt = 0
    }

    private suspend fun awaitQuiet() {
        while (true) {
            val left = lastTypingAt + Config.TYPING_QUIET_MS - SystemClock.elapsedRealtime()
            if (left <= 0) return
            delay(minOf(left, QUIET_POLL_MS))
        }
    }

    override fun unload() {
        cancelBackground()
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

    override fun prepare(chat: ChatContext) {
        val request = prepareRequests.incrementAndGet()
        cancelBackground()
        scope.launch {
            mutex.withLock {
                if (request != prepareRequests.get()) return@withLock
                val loaded = engine ?: return@withLock
                try {
                    ensureConversation(loaded, chat, Config.PREPARE_PROMPT_TOKENS)
                } catch (e: Exception) {
                    closeConversation()
                    if (BuildConfig.DEBUG) Log.d(TAG, "prepare failed: ${e.message}")
                }
            }
        }
    }

    override fun historyBudget(): Int = restoreBudget

    private fun updateBudget() {
        val file = modelFile ?: return
        val bySpeed = (backends.prefillSpeed(file, backendName) * Config.RESTORE_SECONDS).toInt()
        restoreBudget = bySpeed.coerceIn(Config.CONTEXT_TOKENS / 2, contextTokens)
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
        cancelBackground()
        prepareRequests.incrementAndGet()
        mutex.withLock {
            val loaded = engine ?: throw IllegalStateException("model not loaded")
            stopRequested = false
            state.value = EngineState.Generating(backendName)
            try {
                var live = ensureConversation(loaded, request.chat, ContextBuilder.estimate(request.prompt))
                val start = SystemClock.elapsedRealtime()
                var firstAt = 0L
                var chunks = 0
                var attempt = 0
                while (true) {
                    try {
                        live.sendMessageAsync(contentsOf(request.prompt)).collect { message ->
                            val text = textOf(message)
                            if (text.isNotEmpty()) {
                                if (firstAt == 0L) firstAt = SystemClock.elapsedRealtime()
                                chunks++
                                emit(GenEvent.Chunk(text))
                            }
                        }
                        break
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) Log.d(TAG, "generation ended by ${e.javaClass.simpleName}: ${e.message?.take(200)}")
                        if (e is CancellationException && !currentCoroutineContext().isActive) {
                            runCatching { live.cancelProcess() }
                            closeConversation()
                            throw e
                        }
                        if (stopRequested) break
                        if (speculative) modelFile?.let { speculation.markFailed(it, backendName) }
                        if (attempt > 0 || chunks > 0) throw e
                        attempt++
                        closeConversation()
                        live = ensureConversation(loaded, request.chat, ContextBuilder.estimate(request.prompt))
                    }
                }
                val end = SystemClock.elapsedRealtime()
                val bench = runCatching { live.getBenchmarkInfo() }.getOrNull()
                val tokens = bench?.lastDecodeTokenCount?.takeIf { it > 0 } ?: chunks
                val ttft = if (firstAt > 0) firstAt - start else end - start
                val used = runCatching { live.getTokenCount() }.getOrDefault(0)
                if (BuildConfig.DEBUG && bench != null) {
                    Log.d(TAG, "turn prefill=${bench.lastPrefillTokenCount} tok, ttft=$ttft ms, decode=${bench.lastDecodeTokenCount} tok at %.1f tok/s, total=${end - start} ms, ctx=$used".format(bench.lastDecodeTokensPerSecond))
                }
                val decodeSeconds = (end - (if (firstAt > 0) firstAt else start)).coerceAtLeast(1) / 1000.0
                val stats = GenStats(
                    tokens = tokens,
                    promptTokens = bench?.lastPrefillTokenCount ?: 0,
                    ttftMs = ttft,
                    totalMs = end - start,
                    prefillTokensPerSecond = bench?.lastPrefillTokensPerSecond ?: 0.0,
                    decodeTokensPerSecond = bench?.lastDecodeTokensPerSecond?.takeIf { it > 0 } ?: (tokens / decodeSeconds),
                    contextTokens = used,
                )
                val interrupted = stopRequested
                if (interrupted) {
                    closeConversation()
                } else {
                    liveTurns = request.chat.turnCount + 2
                    if (warmAnswer && tokens >= MIN_DECODE_SAMPLE_TOKENS) {
                        modelFile?.let {
                            backends.recordDecode(it, backendName, stats.decodeTokensPerSecond)
                            speculation.record(it, backendName, speculative, stats.decodeTokensPerSecond)
                        }
                    }
                    warmAnswer = true
                }
                emit(GenEvent.Done(stats, interrupted))
                recordMemoryOnce()
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

    override suspend fun title(userText: String, answer: String): String? {
        val prompt = "Write a short title of 2 to 6 words for this conversation. " +
            "Reply with the title only, no quotes and no full stop.\n\n" +
            "User: ${userText.take(TITLE_SOURCE_CHARS)}\nAssistant: ${answer.take(TITLE_SOURCE_CHARS)}"
        return runBackground(prompt, Config.TITLE_TOKENS)?.let(::cleanTitle)
    }

    override suspend fun summarize(previous: String, turns: List<Turn>): String? {
        val transcript = buildString {
            for (turn in turns) {
                append(if (turn.role == Role.USER) "User: " else "Ghost: ")
                if (turn.imagePath != null) append("[shared an image] ")
                append(turn.text.take(SUMMARY_SOURCE_CHARS)).append('\n')
            }
        }
        val prompt = "Update the notes about this conversation. Keep only important facts: the user's name, " +
            "personal details, preferences, decisions, plans, open questions and key details from shared " +
            "documents. Always call the user \"the user\", for example \"The user's name is Alex.\" Do not copy " +
            "general knowledge from Ghost's answers; give each topic at most one short line. " +
            "Write at most 12 short lines, each starting with \"- \". Merge them with the earlier notes and drop " +
            "anything that is no longer true. Reply with the notes only.\n\n" +
            "Earlier notes:\n${previous.ifBlank { "(none)" }}\n\nConversation:\n$transcript"
        return runBackground(prompt, Config.SUMMARY_TOKENS)?.let(::cleanNotes)
    }

    private suspend fun runBackground(prompt: String, maxTokens: Int): String? {
        backgroundCancelled = false
        while (true) {
            awaitQuiet()
            if (backgroundCancelled) return null
            backgroundTyping = false
            val text = withContext(dispatcher) { mutex.withLock { runBackgroundOnce(prompt, maxTokens) } }
            if (backgroundCancelled) return null
            if (!backgroundTyping) return text
            if (BuildConfig.DEBUG) Log.d(TAG, "background task paused for typing")
        }
    }

    private fun runBackgroundOnce(prompt: String, maxTokens: Int): String? {
        val loaded = engine ?: return null
        val config = ConversationConfig(
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0),
            maxOutputToken = maxTokens,
            thinkingConfig = ThinkingConfig(enableThinking = false),
        )
        if (speculative) closeConversation()
        return try {
            val temporary = loaded.createConversation(config)
            background = temporary
            temporary.use { if (backgroundTyping || backgroundCancelled) null else textOf(it.sendMessage(prompt)) }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.d(TAG, "background task stopped: ${e.message}")
            null
        } finally {
            background = null
        }
    }

    private fun cancelBackground() {
        backgroundCancelled = true
        runCatching { background?.cancelProcess() }
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
            fail("no model found", "tap the model name at the top and import a .litertlm model file")
            return
        }
        val check = try {
            modelStore.inspect(file)
        } catch (e: Exception) {
            fail("cannot read ${file.name}: ${e.message}", "tap the model name at the top to pick another model")
            return
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "model check $check, free ${memoryInfo().availMem shr 20} MB")
        backends.consumeCrash(file)?.let { (crashedBackend, crashedContext) ->
            if (crashedContext > Config.CONTEXT_TOKENS) {
                contextSizer.markFailed(file, crashedContext)
                say(Tag.WARN, "last load with a ${crashedContext / 1024}k context crashed, using a smaller one")
            } else {
                backends.markBad(file, crashedBackend)
                say(Tag.WARN, "last load on $crashedBackend crashed, using the other backend")
            }
        }
        candidates = listOf(Backends.CPU, Backends.GPU)
            .filter { check.textBackends.isEmpty() || it in check.textBackends }
            .filter { !backends.isBad(file, it) }
        if (candidates.isEmpty()) {
            fail("no usable backend", "update the app or the phone system and try again")
            return
        }
        val modelMax = check.maxContext.takeIf { it > 0 } ?: Config.CONTEXT_TOKENS
        val size = contextSizer.choose(file, modelMax, memoryInfo().totalMem)
        if (!openWithFallback(file, backends.choose(file, candidates), size, check.vision)) {
            state.value = EngineState.Failed("load failed")
            return
        }
        modelFile = file
        updateBudget()
        model.value = LoadedModel(modelStore.label(file), check.vision, contextTokens)
        state.value = EngineState.Ready(backendName)
    }

    private suspend fun openWithFallback(file: File, name: String, size: Int, vision: Boolean): Boolean {
        if (open(file, name, size, vision)) return true
        if (size > Config.CONTEXT_TOKENS) {
            contextSizer.markFailed(file, size)
            say(Tag.WARN, "${size / 1024}k context did not fit, using ${Config.CONTEXT_TOKENS / 1024}k")
            return openWithFallback(file, name, Config.CONTEXT_TOKENS, vision)
        }
        if (name == Backends.GPU && Backends.CPU in candidates) {
            backends.markBad(file, Backends.GPU)
            backends.store(file, Backends.CPU)
            candidates = listOf(Backends.CPU)
            say(Tag.WARN, "gpu failed, falling back to cpu")
            return open(file, Backends.CPU, size, vision)
        }
        return false
    }

    private suspend fun open(file: File, name: String, size: Int, vision: Boolean): Boolean {
        say(Tag.PENDING, "loading model", "load")
        val start = SystemClock.elapsedRealtime()
        backends.markLoading(file, name, size)
        return try {
            val draft = speculation.enabled(file, name)
            ExperimentalFlags.enableSpeculativeDecoding = if (draft) true else null
            val visionBackend = if (backends.isBad(file, Backends.GPU)) Backends.CPU else Config.VISION_BACKEND
            val config = EngineConfig(
                modelPath = file.absolutePath,
                backend = backends.create(name),
                visionBackend = if (vision) backends.create(visionBackend) else null,
                maxNumTokens = size,
                cacheDir = context.cacheDir.absolutePath,
            )
            val created = Engine(config)
            created.initialize()
            warmUp(created)
            engine = created
            backendName = name
            speculative = draft
            contextTokens = size
            warmAnswer = false
            memoryRecorded = false
            loadMs = SystemClock.elapsedRealtime() - start
            if (BuildConfig.DEBUG) Log.d(TAG, "loaded $name spec=$draft ctx=$size in $loadMs ms, pss=${Debug.getPss() shr 10} MB")
            say(Tag.OK, "model loaded in %.1fs".format(loadMs / 1000.0), "load")
            true
        } catch (e: Exception) {
            say(Tag.FAIL, "$name load failed: ${e.message}", "load")
            false
        } finally {
            backends.clearLoading()
        }
    }

    private fun warmUp(loaded: Engine) {
        val start = SystemClock.elapsedRealtime()
        runCatching { loaded.createConversation(ConversationConfig(maxOutputToken = 1)).use { it.sendMessage("hi") } }
        if (BuildConfig.DEBUG) Log.d(TAG, "warmup ${SystemClock.elapsedRealtime() - start} ms")
    }

    private fun recordMemoryOnce() {
        if (memoryRecorded) return
        memoryRecorded = true
        val file = modelFile ?: return
        val pss = Debug.getPss() * 1024L
        contextSizer.record(file, contextTokens, pss)
        if (BuildConfig.DEBUG) Log.d(TAG, "memory recorded ctx=$contextTokens pss=${pss shr 20} MB")
    }

    private suspend fun ensureConversation(loaded: Engine, chat: ChatContext, promptTokens: Int): Conversation {
        val live = conversation
        val sameChat = liveChatId == chat.chatId || (liveFresh && chat.turnCount == 0)
        if (live != null && sameChat && liveTurns == chat.turnCount) {
            val used = runCatching { live.getTokenCount() }.getOrDefault(Int.MAX_VALUE)
            if (used + promptTokens + maxNewTokens <= contextTokens) {
                liveChatId = chat.chatId
                liveFresh = chat.chatId == null
                return live
            }
        }
        closeConversation()
        val built = ContextBuilder.build(
            chat.history,
            promptTokens,
            systemTokens = ContextBuilder.systemTokens(chat.summary),
            contextTokens = contextTokens,
            maxNewTokens = maxNewTokens,
            historyBudget = historyBudget(),
        )
        val restoring = built.history.isNotEmpty()
        if (restoring) say(Tag.PENDING, "restoring context (${built.history.size} messages)", RESTORE_KEY)
        if (built.dropped > 0 && chat.summary.isBlank()) say(Tag.WARN, "context full, left out ${built.dropped} older messages")
        val start = SystemClock.elapsedRealtime()
        val config = ConversationConfig(
            systemInstruction = Contents.of(Config.systemPrompt() + ContextBuilder.summaryBlock(chat.summary)),
            initialMessages = built.history.map(::messageOf),
            samplerConfig = samplerConfig(),
            prefillPrefaceOnInit = true,
            maxOutputToken = maxNewTokens,
            thinkingConfig = ThinkingConfig(enableThinking = false),
        )
        val created = loaded.createConversation(config)
        val seconds = (SystemClock.elapsedRealtime() - start) / 1000.0
        val prefilled = runCatching { created.getTokenCount() }.getOrDefault(0)
        if (prefilled >= MIN_PREFILL_SAMPLE_TOKENS) {
            modelFile?.let { backends.recordPrefill(it, backendName, prefilled / seconds.coerceAtLeast(0.001)) }
            updateBudget()
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "conversation built with ${built.history.size} of ${chat.history.size} messages, $prefilled tokens in %.2fs, summary=${chat.summary.isNotBlank()}".format(seconds))
        }
        if (restoring) say(Tag.OK, "context restored in %.1fs".format(seconds), RESTORE_KEY)
        conversation = created
        liveChatId = chat.chatId
        liveFresh = chat.chatId == null
        liveTurns = chat.turnCount
        return created
    }

    private fun cleanTitle(raw: String): String? = raw.lineSequence()
        .map { it.trim().removePrefix("Title:").trim().trim('"', '\'', '*', '#', '.', ' ') }
        .firstOrNull { it.isNotEmpty() }
        ?.take(Config.TITLE_MAX_CHARS)

    private fun cleanNotes(raw: String): String? = raw.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("- ") || it.startsWith("* ") }
        .map { "- " + it.drop(2).trim() }
        .take(SUMMARY_MAX_LINES)
        .joinToString("\n")
        .takeIf { it.isNotBlank() }

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

    private companion object {
        const val TAG = "GHOST"
        const val RESTORE_KEY = "restore"
        const val TITLE_SOURCE_CHARS = 600
        const val SUMMARY_SOURCE_CHARS = 2500
        const val SUMMARY_MAX_LINES = 12
        const val MIN_PREFILL_SAMPLE_TOKENS = 128
        const val MIN_DECODE_SAMPLE_TOKENS = 32
        const val QUIET_POLL_MS = 100L
    }
}
