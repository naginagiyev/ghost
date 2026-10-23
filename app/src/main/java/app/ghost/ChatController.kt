package app.ghost

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.ghost.data.ChatRow
import app.ghost.data.ImageInfo
import app.ghost.data.Message
import app.ghost.data.MessageRole
import app.ghost.data.MessageStatus
import app.ghost.engine.ContextBuilder
import app.ghost.engine.ContextTooLongException
import app.ghost.engine.EngineState
import app.ghost.engine.GenEvent
import app.ghost.engine.GenRequest
import app.ghost.engine.GenStats
import app.ghost.engine.Role
import app.ghost.engine.SysLine
import app.ghost.engine.Tag
import app.ghost.engine.Turn
import app.ghost.ui.Command
import app.ghost.ui.Commands
import app.ghost.ui.Input
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

data class EchoItem(override val key: String, val text: String) : Item

data class UserItem(override val key: String, val text: String, val image: ImageInfo?) : Item

data class AssistantItem(override val key: String, val text: String, val footer: String) : Item

enum class Screen { SESSION, CHATS }

sealed interface Effect {
    data object PickImage : Effect
    data object TakePhoto : Effect
    data object PickModel : Effect
    data object ScrollToBottom : Effect
}

@Immutable
data class SessionState(
    val screen: Screen = Screen.SESSION,
    val chatId: Long? = null,
    val items: List<Item> = emptyList(),
    val engine: EngineState = EngineState.Unloaded,
    val contextUsed: Int = 0,
    val busy: Boolean = false,
    val streaming: Boolean = false,
    val streamStartedAt: Long = 0,
    val attachment: ImageInfo? = null,
    val pendingDelete: Boolean = false,
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
    private var messages: List<Message> = emptyList()
    private var generation: Job? = null
    private var lastStats: GenStats? = null
    private var nextKey = 0L
    private val initialLoad: Job

    init {
        viewModelScope.launch { engine.lines.collect(::addLine) }
        viewModelScope.launch {
            var previous = engine.state.value
            engine.state.collect { engineState ->
                _state.update { it.copy(engine = engineState) }
                val becameReady = engineState is EngineState.Ready && previous !is EngineState.Ready
                if (becameReady && previous !is EngineState.Generating) prepareCurrent()
                previous = engineState
            }
        }
        initialLoad = viewModelScope.launch { repository.latestConversation()?.let { openChat(it.id, keepLines = true) } }
    }

    fun submit(raw: String): Boolean {
        val current = _state.value
        if (current.pendingDelete) _state.update { it.copy(pendingDelete = false) }
        when (val input = Commands.parse(raw)) {
            is Input.Run -> {
                echo(raw.trim())
                run(input.command)
            }
            is Input.Unknown -> {
                echo(raw.trim())
                addLine(SysLine(Tag.FAIL, "unknown command ${input.word}, try /help"))
            }
            is Input.Text -> {
                if (input.text.isEmpty() && current.attachment == null) return false
                if (current.busy) return false
                send(input.text, current.attachment)
            }
        }
        return true
    }

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

    fun showSession() = _state.update { it.copy(screen = Screen.SESSION) }

    fun openChat(id: Long) {
        if (refuseWhileBusy()) return
        initialLoad.cancel()
        viewModelScope.launch { openChat(id, keepLines = false) }
    }

    fun newChat() {
        if (refuseWhileBusy()) return
        initialLoad.cancel()
        messages = emptyList()
        _state.update { it.copy(screen = Screen.SESSION, chatId = null, items = emptyList(), contextUsed = 0) }
        addLine(SysLine(Tag.DIM, "new conversation"))
        prepareCurrent()
    }

    fun deleteChat(id: Long) {
        viewModelScope.launch {
            repository.deleteConversation(id)
            if (_state.value.chatId == id) {
                messages = emptyList()
                _state.update { it.copy(chatId = null, items = emptyList(), contextUsed = 0) }
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

    fun confirmDelete(confirmed: Boolean) {
        _state.update { it.copy(pendingDelete = false) }
        val chatId = _state.value.chatId
        if (!confirmed || chatId == null) {
            addLine(SysLine(Tag.INFO, "delete cancelled"))
            return
        }
        viewModelScope.launch {
            repository.deleteConversation(chatId)
            messages = emptyList()
            _state.update { it.copy(chatId = null, items = emptyList(), contextUsed = 0) }
            addLine(SysLine(Tag.OK, "conversation deleted"))
            prepareCurrent()
        }
    }

    fun clearAttachment() {
        val old = _state.value.attachment ?: return
        _state.update { it.copy(attachment = null) }
        viewModelScope.launch { app.imageStore.delete(old.path) }
    }

    fun pickImage() {
        effectChannel.trySend(Effect.PickImage)
    }

    fun takePhoto() {
        effectChannel.trySend(Effect.TakePhoto)
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

    private suspend fun openChat(id: Long, keepLines: Boolean) {
        val loaded = repository.messages(id)
        val chatItems = withContext(Dispatchers.IO) { loaded.map { it.toItem() } }
        messages = loaded
        val turns = loaded.mapNotNull { it.toTurn() }
        _state.update {
            val lines = if (keepLines) it.items.filter { item -> item is SystemItem || item is EchoItem } else emptyList()
            it.copy(
                screen = Screen.SESSION,
                chatId = id,
                items = chatItems + lines,
                contextUsed = ContextBuilder.estimateAll(turns),
            )
        }
        effectChannel.trySend(Effect.ScrollToBottom)
        prepareCurrent()
    }

    private fun prepareCurrent() {
        if (engine.state.value !is EngineState.Ready) return
        engine.prepare(_state.value.chatId, messages.mapNotNull { it.toTurn() })
    }

    private fun refuseWhileBusy(): Boolean {
        if (!_state.value.busy) return false
        addLine(SysLine(Tag.WARN, "busy, press stop first"))
        return true
    }

    private fun run(command: Command) {
        when (command) {
            Command.NEW -> newChat()
            Command.CHATS -> showChats()
            Command.IMPORT -> if (!refuseWhileBusy()) effectChannel.trySend(Effect.PickModel)
            Command.STATS -> viewModelScope.launch { printStats() }
            Command.UNLOAD -> if (!refuseWhileBusy()) engine.unload()
            Command.RELOAD -> if (!refuseWhileBusy()) {
                engine.unload()
                engine.load()
            }
            Command.DELETE -> askDelete()
            Command.HELP -> Commands.helpLines().forEach { addLine(SysLine(Tag.PLAIN, it)) }
        }
    }

    private suspend fun printStats() {
        engine.stats().forEach { addLine(SysLine(Tag.PLAIN, it)) }
        val stats = lastStats
        if (stats == null) {
            addLine(SysLine(Tag.PLAIN, "last run   none"))
        } else {
            addLine(SysLine(Tag.PLAIN, "last run   ${stats.tokens} tok, ttft ${stats.ttftMs} ms, total ${stats.totalMs} ms"))
            addLine(
                SysLine(
                    Tag.PLAIN,
                    "speed      prefill %.1f tok/s, decode %.1f tok/s".format(
                        stats.prefillTokensPerSecond,
                        stats.decodeTokensPerSecond,
                    ),
                )
            )
        }
        addLine(SysLine(Tag.PLAIN, "context    ${_state.value.contextUsed}/${Config.CONTEXT_TOKENS} tokens"))
    }

    private fun askDelete() {
        val chatId = _state.value.chatId
        if (chatId == null) {
            addLine(SysLine(Tag.INFO, "nothing to delete"))
            return
        }
        if (refuseWhileBusy()) return
        _state.update { it.copy(pendingDelete = true) }
    }

    private fun send(text: String, attachment: ImageInfo?) {
        _state.update { it.copy(busy = true, attachment = null) }
        generation = viewModelScope.launch {
            try {
                initialLoad.join()
                val chatId = _state.value.chatId ?: repository.createConversation(text).id
                val history = messages.mapNotNull { it.toTurn() }
                val now = System.currentTimeMillis()
                val userMessage = repository.addMessage(
                    Message(
                        conversationId = chatId,
                        role = MessageRole.USER,
                        text = text,
                        imagePath = attachment?.path,
                        status = MessageStatus.DONE,
                        createdAt = now,
                        genMs = null,
                        tokens = null,
                    )
                )
                messages = messages + userMessage
                _state.update { it.copy(chatId = chatId, items = it.items + UserItem(newKey(), text, attachment)) }
                effectChannel.trySend(Effect.ScrollToBottom)
                if (awaitReady()) generate(GenRequest(chatId, history, Turn(Role.USER, text, attachment?.path)))
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
    private suspend fun generate(request: GenRequest) {
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
                finishGeneration(request.chatId, builder.toString(), done, failure)
                if (BuildConfig.DEBUG) {
                    val seconds = (System.currentTimeMillis() - startedAt) / 1000.0
                    Log.d("GHOST", "ui publishes=$publishes in %.1fs".format(seconds))
                }
            }
        }
    }

    private suspend fun finishGeneration(chatId: Long, text: String, done: GenEvent.Done?, failure: Throwable?) {
        val stats = done?.stats
        if (stats != null) lastStats = stats
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
        if (status == MessageStatus.DONE && text.isNotEmpty() && firstAnswer) {
            generateTitle(chatId, messages.first { it.role == MessageRole.USER }.text, text)
        } else if (status != MessageStatus.DONE || text.isEmpty()) {
            prepareCurrent()
        }
    }

    private fun generateTitle(chatId: Long, userText: String, answer: String) {
        viewModelScope.launch {
            engine.title(userText, answer)?.let { repository.setGeneratedTitle(chatId, it) }
            prepareCurrent()
        }
    }

    private fun echo(text: String) {
        _state.update { it.copy(items = it.items + EchoItem(newKey(), text)) }
        effectChannel.trySend(Effect.ScrollToBottom)
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
        if (text.isEmpty() && imagePath == null) return null
        val role = if (role == MessageRole.USER) Role.USER else Role.ASSISTANT
        return Turn(role, text, imagePath)
    }

    private fun Message.toItem(): Item {
        if (role == MessageRole.USER) return UserItem("m$id", text, imagePath?.let { app.imageStore.info(it) })
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
