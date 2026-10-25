package app.ghost

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.ghost.data.ChatRow
import app.ghost.data.DocInfo
import app.ghost.data.ImageInfo
import app.ghost.data.Message
import app.ghost.data.MessageRole
import app.ghost.data.MessageStatus
import app.ghost.data.tokenText
import app.ghost.engine.ChatContext
import app.ghost.engine.ContextBuilder
import app.ghost.engine.ContextTooLongException
import app.ghost.engine.EngineState
import app.ghost.engine.GenEvent
import app.ghost.engine.GenRequest
import app.ghost.engine.LoadedModel
import app.ghost.engine.ModelFile
import app.ghost.engine.Role
import app.ghost.engine.SysLine
import app.ghost.engine.Tag
import app.ghost.engine.Turn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Immutable
sealed interface Item {
    val key: String
}

data class SystemItem(override val key: String, val tag: Tag, val text: String, val lineKey: String?) : Item

data class UserItem(override val key: String, val text: String, val image: ImageInfo?, val doc: DocInfo?) : Item

data class AssistantItem(override val key: String, val text: String, val footer: String) : Item

enum class Screen { SESSION, CHATS, MODELS }

sealed interface Effect {
    data object PickImage : Effect
    data object TakePhoto : Effect
    data object PickDocument : Effect
    data object PickModel : Effect
    data object ScrollToBottom : Effect
}

@Immutable
data class Draft(val id: Long, val text: String)

@Immutable
data class SessionState(
    val screen: Screen = Screen.SESSION,
    val chatId: Long? = null,
    val items: List<Item> = emptyList(),
    val engine: EngineState = EngineState.Unloaded,
    val model: LoadedModel? = null,
    val models: List<ModelFile> = emptyList(),
    val contextUsed: Int = 0,
    val busy: Boolean = false,
    val streaming: Boolean = false,
    val streamStartedAt: Long = 0,
    val attachment: ImageInfo? = null,
    val document: DocInfo? = null,
    val editing: Boolean = false,
    val draft: Draft? = null,
)

class ChatController(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val engine get() = app.engine
    private val repository = app.repository
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state
    private val _stream = MutableStateFlow("")
    val stream: StateFlow<String> = _stream
    val chats: Flow<List<ChatRow>> = repository.chatRows
    private val effectChannel = Channel<Effect>(Channel.BUFFERED)
    val effects: Flow<Effect> = effectChannel.receiveAsFlow()
    private val streamDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val docTexts = HashMap<String, String>()
    private var messages: List<Message> = emptyList()
    private var summary = ""
    private var summaryUpTo = 0L
    private var generation: Job? = null
    private var nextKey = 0L
    private val initialLoad: Job

    init {
        viewModelScope.launch { engine.lines.collect(::addLine) }
        viewModelScope.launch { engine.model.collect { loaded -> _state.update { it.copy(model = loaded) } } }
        viewModelScope.launch {
            var previous = engine.state.value
            engine.state.collect { engineState ->
                _state.update { it.copy(engine = engineState) }
                val becameReady = engineState is EngineState.Ready && previous !is EngineState.Ready
                if (becameReady && previous !is EngineState.Generating && !_state.value.busy) prepareCurrent()
                previous = engineState
            }
        }
        initialLoad = viewModelScope.launch { repository.latestConversation()?.let { openChat(it.id, keepLines = true) } }
    }

    fun submit(raw: String): Boolean {
        val text = raw.trim()
        val current = _state.value
        if (text.isEmpty() && current.attachment == null && current.document == null) return false
        if (current.busy) return false
        engine.userWaiting()
        if (current.editing) editLast(text) else send(text, current.attachment, current.document)
        return true
    }

    fun typing() = engine.userTyping()

    fun stop() {
        if (_state.value.streaming) {
            engine.stop()
        } else if (generation?.isActive == true) {
            generation?.cancel()
            addLine(SysLine(Tag.WARN, "cancelled", key = "wait"))
        }
    }

    fun copied() = addLine(SysLine(Tag.INFO, "message copied"))

    fun showChats() {
        if (!refuseWhileBusy()) _state.update { it.copy(screen = Screen.CHATS) }
    }

    fun showModels() {
        if (refuseWhileBusy()) return
        viewModelScope.launch {
            refreshModels()
            _state.update { it.copy(screen = Screen.MODELS) }
        }
    }

    private suspend fun refreshModels() {
        val models = withContext(Dispatchers.IO) { app.modelStore.list() }
        _state.update { it.copy(models = models) }
    }

    fun showSession() = _state.update { it.copy(screen = Screen.SESSION) }

    fun switchModel(model: ModelFile) {
        showSession()
        if (model.active || refuseWhileBusy()) return
        app.modelStore.setActive(model.file)
        addLine(SysLine(Tag.DIM, "switching to ${model.label}"))
        engine.unload()
        engine.load()
    }

    fun deleteModel(model: ModelFile) {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { app.modelStore.delete(model.file) }
            if (deleted) addLine(SysLine(Tag.OK, "deleted model ${model.label}"))
            refreshModels()
        }
    }

    fun importModel() {
        if (!refuseWhileBusy()) effectChannel.trySend(Effect.PickModel)
    }

    fun openChat(id: Long) {
        if (refuseWhileBusy()) return
        initialLoad.cancel()
        viewModelScope.launch { openChat(id, keepLines = false) }
    }

    fun newChat() {
        if (refuseWhileBusy()) return
        initialLoad.cancel()
        resetChat()
        _state.update { it.copy(screen = Screen.SESSION) }
        addLine(SysLine(Tag.DIM, "new conversation"))
        prepareCurrent()
    }

    fun deleteChat(id: Long) {
        viewModelScope.launch {
            repository.deleteConversation(id)
            if (_state.value.chatId == id) {
                resetChat()
                prepareCurrent()
            }
        }
    }

    fun renameChat(id: Long, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch { repository.renameConversation(id, title) }
    }

    fun setPinned(id: Long, pinned: Boolean) {
        viewModelScope.launch { repository.setPinned(id, pinned) }
    }

    fun startEdit(item: UserItem) {
        val last = messages.lastOrNull { it.role == MessageRole.USER } ?: return
        if ("m${last.id}" != item.key || refuseWhileBusy()) return
        _state.update { it.copy(editing = true, draft = Draft(System.nanoTime(), last.text)) }
    }

    fun cancelEdit() = _state.update { it.copy(editing = false, draft = Draft(System.nanoTime(), "")) }

    fun clearAttachment() {
        val old = _state.value.attachment ?: return
        _state.update { it.copy(attachment = null) }
        viewModelScope.launch { app.imageStore.delete(old.path) }
    }

    fun clearDocument() {
        val old = _state.value.document ?: return
        _state.update { it.copy(document = null) }
        docTexts.remove(old.path)
        viewModelScope.launch { app.docStore.delete(old.path) }
    }

    fun pickImage() {
        if (canSeeImages()) effectChannel.trySend(Effect.PickImage)
    }

    fun takePhoto() {
        if (canSeeImages()) effectChannel.trySend(Effect.TakePhoto)
    }

    fun pickDocument() {
        effectChannel.trySend(Effect.PickDocument)
    }

    fun onPhotoTaken(file: File) {
        viewModelScope.launch {
            onImagePicked(Uri.fromFile(file)).join()
            withContext(Dispatchers.IO) { file.delete() }
        }
    }

    fun cameraUnavailable() = addLine(SysLine(Tag.FAIL, "no camera app found"))

    suspend fun thumbnail(path: String) = app.imageStore.thumbnail(path, THUMBNAIL_SIDE)

    fun onImagePicked(uri: Uri, thenSend: String? = null): Job =
        viewModelScope.launch {
            try {
                val info = app.imageStore.save(uri)
                val old = _state.value.attachment
                _state.update { it.copy(attachment = info) }
                old?.let { app.imageStore.delete(it.path) }
                thenSend?.let { submit(it) }
            } catch (e: Exception) {
                addLine(SysLine(Tag.FAIL, "could not read image: ${e.message}"))
            }
        }

    fun onDocumentPicked(uri: Uri, thenSend: String? = null): Job =
        viewModelScope.launch {
            addLine(SysLine(Tag.PENDING, "reading document", key = "doc"))
            try {
                val info = app.docStore.save(uri, documentCharLimit())
                docTexts[info.path] = withContext(Dispatchers.IO) { app.docStore.read(info.path) }
                _state.value.document?.let { clearDocument() }
                _state.update { it.copy(document = info) }
                addLine(SysLine(Tag.OK, "read ${info.name}, about ${tokenText(info.chars)} tokens", key = "doc"))
                if (info.truncated) addLine(SysLine(Tag.WARN, "document is longer than this model's memory, only the first part was kept"))
                thenSend?.let { submit(it) }
            } catch (e: Exception) {
                addLine(SysLine(Tag.FAIL, "could not read document: ${e.message}", key = "doc"))
            }
        }

    fun onModelPicked(uri: Uri) {
        if (refuseWhileBusy()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                engine.unload()
                engine.state.first { it == EngineState.Unloaded }
                addLine(SysLine(Tag.PENDING, "importing model 0%", key = "import"))
                val result = app.modelStore.import(uri) { percent ->
                    addLine(SysLine(Tag.PENDING, "importing model $percent%", key = "import"))
                }
                val size = result.file.length() shr 20
                addLine(SysLine(Tag.OK, "imported ${result.file.name}, $size MB", key = "import"))
                addLine(SysLine(Tag.DIM, "sha256 ${result.sha256}"))
            } catch (e: Exception) {
                addLine(SysLine(Tag.FAIL, "import failed: ${e.message}", key = "import"))
            } finally {
                _state.update { it.copy(busy = false) }
                engine.load()
            }
        }
    }

    private fun canSeeImages(): Boolean {
        if (_state.value.model?.vision != false) return true
        addLine(SysLine(Tag.FAIL, "this model can't see images, tap the model name at the top to switch"))
        return false
    }

    private fun documentCharLimit(): Int {
        val contextTokens = _state.value.model?.contextTokens ?: Config.CONTEXT_TOKENS
        val tokens = contextTokens - ContextBuilder.systemTokens(summary) - Config.MAX_NEW_TOKENS - Config.DOC_PROMPT_RESERVE
        return (tokens.coerceAtLeast(500) * Config.CHARS_PER_TOKEN).toInt()
    }

    private fun resetChat() {
        messages = emptyList()
        summary = ""
        summaryUpTo = 0
        _state.update { it.copy(chatId = null, items = emptyList(), contextUsed = 0, editing = false) }
    }

    private suspend fun openChat(id: Long, keepLines: Boolean) {
        val conversation = repository.conversation(id)
        val loaded = repository.messages(id)
        val chatItems = withContext(Dispatchers.IO) {
            loaded.mapNotNull { message -> message.docPath?.let { path -> path to app.docStore.read(path) } }
                .forEach { (path, text) -> docTexts[path] = text }
            loaded.map { it.toItem() }
        }
        messages = loaded
        summary = conversation?.summary.orEmpty()
        summaryUpTo = conversation?.summaryUpTo ?: 0
        _state.update {
            val lines = if (keepLines) it.items.filter { item -> item is SystemItem } else emptyList()
            it.copy(
                screen = Screen.SESSION,
                chatId = id,
                items = chatItems + lines,
                contextUsed = ContextBuilder.estimateAll(historyTurns(), summary),
                editing = false,
            )
        }
        effectChannel.trySend(Effect.ScrollToBottom)
        prepareCurrent()
    }

    private fun chatContext(): ChatContext = ChatContext(
        chatId = _state.value.chatId,
        turnCount = messages.count { it.toTurn() != null },
        history = historyTurns(),
        summary = summary,
    )

    private fun historyTurns(): List<Turn> = messages.filter { it.id > summaryUpTo }.mapNotNull { it.toTurn() }

    private fun prepareCurrent() {
        if (engine.state.value !is EngineState.Ready) return
        engine.prepare(chatContext())
    }

    private fun refuseWhileBusy(): Boolean {
        if (!_state.value.busy) return false
        addLine(SysLine(Tag.WARN, "busy, press stop first"))
        return true
    }

    private fun editLast(text: String) {
        val lastUser = messages.lastOrNull { it.role == MessageRole.USER }
        _state.update { it.copy(editing = false) }
        if (lastUser == null) return
        val removed = messages.filter { it.createdAt >= lastUser.createdAt && it.id >= lastUser.id }
        val keys = removed.map { "m${it.id}" }.toSet()
        val image = lastUser.imagePath?.let { app.imageStore.info(it) }
        val doc = lastUser.docPath?.let { app.docStore.info(it, lastUser.docName.orEmpty()) }
        messages = messages - removed.toSet()
        _state.update { current -> current.copy(items = current.items.filterNot { it.key in keys }) }
        viewModelScope.launch {
            repository.deleteMessages(removed)
            send(text, image, doc)
        }
    }

    private fun send(text: String, attachment: ImageInfo?, document: DocInfo?) {
        _state.update { it.copy(busy = true, attachment = null, document = null) }
        generation = viewModelScope.launch {
            try {
                initialLoad.join()
                val question = if (text.isEmpty() && document != null) Config.DOC_DEFAULT_QUESTION else text
                val chatId = _state.value.chatId ?: repository.createConversation(question).id
                val context = chatContext().copy(chatId = chatId)
                val userMessage = repository.addMessage(
                    Message(
                        conversationId = chatId,
                        role = MessageRole.USER,
                        text = question,
                        imagePath = attachment?.path,
                        status = MessageStatus.DONE,
                        createdAt = System.currentTimeMillis(),
                        genMs = null,
                        tokens = null,
                        docPath = document?.path,
                        docName = document?.name,
                    )
                )
                messages = messages + userMessage
                _state.update { it.copy(chatId = chatId, items = it.items + UserItem("m${userMessage.id}", question, attachment, document)) }
                effectChannel.trySend(Effect.ScrollToBottom)
                val prompt = userMessage.toTurn() ?: Turn(Role.USER, question)
                if (awaitReady()) generate(GenRequest(context, prompt), userMessage)
            } finally {
                _state.update { it.copy(busy = false, streaming = false) }
            }
        }
    }

    private suspend fun awaitReady(): Boolean {
        val current = engine.state.value
        if (current is EngineState.Ready) return true
        if (current is EngineState.Unloaded) engine.load()
        addLine(SysLine(Tag.PENDING, "waiting for model", key = "wait"))
        val result = engine.state.first { it is EngineState.Ready || it is EngineState.Failed }
        if (result is EngineState.Failed) {
            addLine(SysLine(Tag.FAIL, "model not loaded, message kept", key = "wait"))
            return false
        }
        addLine(SysLine(Tag.OK, "waiting for model", key = "wait"))
        return true
    }

    @OptIn(FlowPreview::class)
    private suspend fun generate(request: GenRequest, userMessage: Message) {
        val builder = StringBuilder()
        var done: GenEvent.Done? = null
        var failure: Throwable? = null
        var publishes = 0
        val startedAt = System.currentTimeMillis()
        _stream.value = ""
        _state.update { it.copy(streaming = true, streamStartedAt = startedAt) }
        try {
            withContext(streamDispatcher) {
                coroutineScope {
                    val ticks = MutableStateFlow(0)
                    val publisher = launch {
                        ticks.sample(Config.STREAM_UI_INTERVAL_MS).collect {
                            _stream.value = builder.toString()
                            publishes++
                        }
                    }
                    engine.generate(request).collect { event ->
                        when (event) {
                            is GenEvent.Chunk -> {
                                builder.append(event.text)
                                ticks.value++
                            }
                            is GenEvent.Done -> done = event
                        }
                    }
                    publisher.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failure = e
        } finally {
            withContext(NonCancellable) {
                finishGeneration(request.chat.chatId ?: userMessage.conversationId, userMessage, builder.toString(), done, failure)
                if (BuildConfig.DEBUG) {
                    val seconds = (System.currentTimeMillis() - startedAt) / 1000.0
                    Log.d("GHOST", "ui publishes=$publishes in %.1fs".format(seconds))
                }
            }
        }
    }

    private suspend fun finishGeneration(chatId: Long, userMessage: Message, text: String, done: GenEvent.Done?, failure: Throwable?) {
        val stats = done?.stats
        if (stats != null && stats.promptTokens > 0) {
            repository.setTokens(userMessage.id, stats.promptTokens)
            messages = messages.map { if (it.id == userMessage.id) it.copy(tokens = stats.promptTokens) else it }
        }
        val status = when {
            failure != null -> MessageStatus.ERROR
            done == null || done.interrupted -> MessageStatus.INTERRUPTED
            else -> MessageStatus.DONE
        }
        if (text.isNotEmpty()) {
            val saved = repository.addMessage(
                Message(
                    conversationId = chatId,
                    role = MessageRole.ASSISTANT,
                    text = text,
                    imagePath = null,
                    status = status,
                    createdAt = System.currentTimeMillis(),
                    genMs = stats?.totalMs,
                    tokens = stats?.tokens,
                )
            )
            messages = messages + saved
            _state.update { it.copy(items = it.items + saved.toItem()) }
        }
        _state.update {
            it.copy(streaming = false, contextUsed = stats?.contextTokens?.takeIf { used -> used > 0 } ?: it.contextUsed)
        }
        when {
            failure is ContextTooLongException -> addLine(SysLine(Tag.FAIL, "message too long for context"))
            failure != null -> addLine(SysLine(Tag.FAIL, "generation failed: ${failure.message}"))
            status == MessageStatus.INTERRUPTED -> addLine(SysLine(Tag.INFO, "interrupted"))
        }
        val firstAnswer = messages.count { it.role == MessageRole.ASSISTANT } == 1
        when {
            status == MessageStatus.DONE && text.isNotEmpty() && firstAnswer -> generateTitle(chatId, userMessage.text, text)
            status == MessageStatus.DONE && text.isNotEmpty() -> summarizeIfNeeded(chatId)
            else -> prepareCurrent()
        }
    }

    private fun generateTitle(chatId: Long, userText: String, answer: String) {
        viewModelScope.launch {
            engine.title(userText, answer)?.let { repository.setGeneratedTitle(chatId, it) }
            prepareCurrent()
        }
    }

    private fun summarizeIfNeeded(chatId: Long) {
        val contextTokens = _state.value.model?.contextTokens ?: Config.CONTEXT_TOKENS
        val pending = messages.filter { it.id > summaryUpTo }.mapNotNull { message -> message.toTurn()?.let { message to it } }
        val built = ContextBuilder.build(
            pending.map { it.second },
            Config.PREPARE_PROMPT_TOKENS,
            systemTokens = ContextBuilder.systemTokens(summary),
            contextTokens = contextTokens,
            maxNewTokens = Config.MAX_NEW_TOKENS,
            historyBudget = engine.historyBudget(),
        )
        if (built.dropped < Config.SUMMARY_MIN_MESSAGES) return
        val dropped = pending.take(built.dropped)
        val previous = summary
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            val notes = engine.summarize(previous, dropped.map { it.second })
            if (notes != null && _state.value.chatId == chatId) {
                summary = notes
                summaryUpTo = dropped.last().first.id
                repository.setSummary(chatId, notes, summaryUpTo)
                if (BuildConfig.DEBUG) {
                    Log.d("GHOST", "summarized ${dropped.size} messages in ${System.currentTimeMillis() - started} ms:\n$notes")
                }
            }
            prepareCurrent()
        }
    }

    private fun addLine(line: SysLine) {
        _state.update { current ->
            val index = if (line.key == null) -1 else current.items.indexOfLast {
                it is SystemItem && it.lineKey == line.key && it.tag == Tag.PENDING
            }
            if (index >= 0) {
                val updated = current.items.toMutableList()
                updated[index] = SystemItem(current.items[index].key, line.tag, line.text, line.key)
                current.copy(items = updated)
            } else {
                current.copy(items = current.items + SystemItem(newKey(), line.tag, line.text, line.key))
            }
        }
    }

    private fun newKey(): String = "k${nextKey++}"

    private fun Message.toTurn(): Turn? {
        if (text.isEmpty() && imagePath == null && docPath == null) return null
        val role = if (role == MessageRole.USER) Role.USER else Role.ASSISTANT
        val document = docPath?.let { "Document \"${docName.orEmpty()}\":\n<<<\n${docTexts[it].orEmpty()}\n>>>\n\n" }.orEmpty()
        return Turn(role, document + text, imagePath, tokens)
    }

    private fun Message.toItem(): Item {
        if (role == MessageRole.USER) {
            val image = imagePath?.let { app.imageStore.info(it) }
            val doc = docPath?.let { app.docStore.info(it, docName.orEmpty()) }
            return UserItem("m$id", text, image, doc)
        }
        return AssistantItem("m$id", text, footer(this))
    }

    private fun footer(message: Message): String = when (message.status) {
        MessageStatus.INTERRUPTED -> "── interrupted"
        MessageStatus.ERROR -> "── error"
        else -> ""
    }

    private companion object {
        const val THUMBNAIL_SIDE = 320
    }
}
