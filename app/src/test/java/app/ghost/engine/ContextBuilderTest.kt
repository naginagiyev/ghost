package app.ghost.engine

import app.ghost.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBuilderTest {
    private fun user(chars: Int) = Turn(Role.USER, "u".repeat(chars))
    private fun assistant(chars: Int) = Turn(Role.ASSISTANT, "a".repeat(chars))

    @Test
    fun estimateUsesCharsPerTokenPlusOverhead() {
        assertEquals(10 + Config.TURN_OVERHEAD_TOKENS, ContextBuilder.estimate(user(35)))
        assertEquals(1 + Config.TURN_OVERHEAD_TOKENS, ContextBuilder.estimate(user(1)))
    }

    @Test
    fun imageAddsFixedCost() {
        val turn = Turn(Role.USER, "", imagePath = "/x.jpg")
        assertEquals(Config.IMAGE_TOKEN_COST + Config.TURN_OVERHEAD_TOKENS, ContextBuilder.estimate(turn))
    }

    @Test
    fun keepsWholeHistoryWhenItFits() {
        val history = listOf(user(70), assistant(70), user(70), assistant(70))
        val built = ContextBuilder.build(history, ContextBuilder.estimate(user(35)), systemTokens = 50, contextTokens = 1000, maxNewTokens = 100)
        assertEquals(history, built.history)
        assertEquals(0, built.dropped)
        assertEquals(50 + 4 * 24 + 14, built.estimatedTokens)
    }

    @Test
    fun dropsOldestTurnsFirst() {
        val history = listOf(user(350), assistant(350), user(35), assistant(35))
        val built = ContextBuilder.build(history, ContextBuilder.estimate(user(35)), systemTokens = 0, contextTokens = 100, maxNewTokens = 50)
        assertEquals(listOf(user(35), assistant(35)), built.history)
        assertEquals(2, built.dropped)
    }

    @Test
    fun keptHistoryStartsWithUserTurn() {
        val history = listOf(user(700), assistant(35), user(35), assistant(35))
        val built = ContextBuilder.build(history, ContextBuilder.estimate(user(35)), systemTokens = 0, contextTokens = 110, maxNewTokens = 50)
        assertTrue(built.history.isEmpty() || built.history.first().role == Role.USER)
        assertEquals(listOf(user(35), assistant(35)), built.history)
    }

    @Test
    fun latestMessageAlwaysKeptWithEmptyHistory() {
        val built = ContextBuilder.build(emptyList(), ContextBuilder.estimate(user(35)), systemTokens = 10, contextTokens = 100, maxNewTokens = 50)
        assertEquals(emptyList<Turn>(), built.history)
        assertEquals(10 + 14, built.estimatedTokens)
    }

    @Test(expected = ContextTooLongException::class)
    fun latestMessageTooLongThrows() {
        ContextBuilder.build(emptyList(), ContextBuilder.estimate(user(3500)), systemTokens = 10, contextTokens = 1000, maxNewTokens = 100)
    }
}
