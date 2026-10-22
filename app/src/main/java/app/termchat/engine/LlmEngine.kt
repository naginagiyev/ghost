package app.termchat.engine

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

enum class Tag { OK, PENDING, WARN, FAIL, INFO, DIM }

data class SysLine(val tag: Tag, val text: String, val key: String? = null)

enum class Role { USER, ASSISTANT }

data class Turn(val role: Role, val text: String, val imagePath: String? = null)

data class GenRequest(val chatId: Long, val history: List<Turn>, val prompt: Turn)

data class GenStats(
    val tokens: Int,
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
    fun load()
    fun unload()
    fun stop()
    fun generate(request: GenRequest): Flow<GenEvent>
    fun calibrate()
    fun bench()
    suspend fun stats(): List<String>
    fun setThrottled(throttled: Boolean)
}
