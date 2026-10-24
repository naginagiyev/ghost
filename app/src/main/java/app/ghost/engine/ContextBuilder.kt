package app.ghost.engine

import app.ghost.Config
import kotlin.math.ceil

data class BuiltContext(val history: List<Turn>, val estimatedTokens: Int, val dropped: Int)

object ContextBuilder {
    fun estimateText(text: String): Int = ceil(text.length / Config.CHARS_PER_TOKEN).toInt()

    fun estimate(turn: Turn): Int {
        turn.tokens?.let { return it }
        val image = if (turn.imagePath != null) Config.IMAGE_TOKEN_COST else 0
        return estimateText(turn.text) + Config.TURN_OVERHEAD_TOKENS + image
    }

    fun systemTokens(summary: String = ""): Int =
        estimateText(Config.systemPrompt() + summaryBlock(summary)) + Config.TURN_OVERHEAD_TOKENS

    fun summaryBlock(summary: String): String =
        if (summary.isBlank()) "" else "\n\nWhat you remember about the user and this chat from earlier:\n$summary"

    fun estimateAll(turns: List<Turn>, summary: String = ""): Int = systemTokens(summary) + turns.sumOf(::estimate)

    fun build(
        history: List<Turn>,
        promptTokens: Int,
        systemTokens: Int = systemTokens(),
        contextTokens: Int = Config.CONTEXT_TOKENS,
        maxNewTokens: Int = Config.MAX_NEW_TOKENS,
        historyBudget: Int = Int.MAX_VALUE,
    ): BuiltContext {
        val budget = contextTokens - systemTokens - maxNewTokens
        var used = promptTokens
        if (used > budget) throw ContextTooLongException()
        val limit = minOf(budget.toLong(), promptTokens.toLong() + historyBudget).toInt()
        var start = history.size
        while (start > 0) {
            val cost = estimate(history[start - 1])
            if (used + cost > limit) break
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
