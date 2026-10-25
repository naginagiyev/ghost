package app.ghost.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface EngineState {
    data object Unloaded : EngineState
    data object Loading : EngineState
    data class Ready(val backend: String) : EngineState
    data class Generating(val backend: String) : EngineState
    data class Failed(val reason: String) : EngineState
}

enum class Tag { OK, PENDING, WARN, FAIL, INFO, PLAIN, DIM }

data class SysLine(val tag: Tag, val text: String, val key: String? = null)

enum class Role { USER, ASSISTANT }

data class Turn(val role: Role, val text: String, val imagePath: String? = null, val tokens: Int? = null)

data class ChatContext(val chatId: Long?, val turnCount: Int, val history: List<Turn>, val summary: String)

data class GenRequest(val chat: ChatContext, val prompt: Turn)

data class LoadedModel(val label: String, val vision: Boolean, val contextTokens: Int)

data class GenStats(
    val tokens: Int,
    val promptTokens: Int,
    val ttftMs: Long,
    val totalMs: Long,
    val prefillTokensPerSecond: Double,
    val decodeTokensPerSecond: Double,
    val contextTokens: Int,
)

sealed interface GenEvent {
    data class Chunk(val text: String) : GenEvent
    data class Done(val stats: GenStats, val interrupted: Boolean) : GenEvent
}

class ContextTooLongException : IllegalStateException("message too long for context")

interface LlmEngine {
    val state: StateFlow<EngineState>
    val lines: SharedFlow<SysLine>
    val model: StateFlow<LoadedModel?>
    fun load()
    fun unload()
    fun stop()
    fun prepare(chat: ChatContext)
    fun generate(request: GenRequest): Flow<GenEvent>
    suspend fun title(userText: String, answer: String): String?
    suspend fun summarize(previous: String, turns: List<Turn>): String?
    fun historyBudget(): Int
    fun userTyping()
    fun userWaiting()
    fun setThrottled(throttled: Boolean)
}
