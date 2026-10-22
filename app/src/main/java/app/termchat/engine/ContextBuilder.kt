package app.termchat.engine

import app.termchat.Config
import kotlin.math.ceil

data class BuiltContext(val history: List<Turn>, val estimatedTokens: Int, val dropped: Int)

object ContextBuilder {
    fun estimateText(text: String): Int = ceil(text.length / Config.CHARS_PER_TOKEN).toInt()

    fun estimate(turn: Turn): Int {
        val image = if (turn.imagePath != null) Config.IMAGE_TOKEN_COST else 0
        return estimateText(turn.text) + Config.TURN_OVERHEAD_TOKENS + image
    }

    fun systemTokens(): Int = estimateText(Config.systemPrompt()) + Config.TURN_OVERHEAD_TOKENS

    fun estimateAll(turns: List<Turn>): Int = systemTokens() + turns.sumOf(::estimate)

    fun build(
        history: List<Turn>,
        prompt: Turn,
        systemTokens: Int = systemTokens(),
        contextTokens: Int = Config.CONTEXT_TOKENS,
        maxNewTokens: Int = Config.MAX_NEW_TOKENS,
    ): BuiltContext {
        val budget = contextTokens - systemTokens - maxNewTokens
        var used = estimate(prompt)
        if (used > budget) throw ContextTooLongException()
        var start = history.size
        while (start > 0) {
            val cost = estimate(history[start - 1])
            if (used + cost > budget) break
            used += cost
            start--
        }
        while (start < history.size && history[start].role != Role.USER) {
            used -= estimate(history[start])
            start++
        }
        val kept = history.subList(start, history.size).toList()
        return BuiltContext(kept, used + systemTokens, start)
    }
}
