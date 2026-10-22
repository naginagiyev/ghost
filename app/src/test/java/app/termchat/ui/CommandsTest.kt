package app.termchat.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandsTest {
    @Test
    fun parsesKnownCommand() {
        assertEquals(Input.Run(Command.STATS), Commands.parse("/stats"))
        assertEquals(Input.Run(Command.NEW), Commands.parse("  /NEW  "))
    }

    @Test
    fun unknownCommandIsReported() {
        assertEquals(Input.Unknown("/nope"), Commands.parse("/nope"))
    }

    @Test
    fun plainTextIsNotACommand() {
        assertEquals(Input.Text("hello"), Commands.parse(" hello "))
    }

    @Test
    fun multilineTextStartingWithSlashIsText() {
        assertEquals(Input.Text("/usr/bin\nis a path"), Commands.parse("/usr/bin\nis a path"))
    }

    @Test
    fun suggestionsMatchPrefix() {
        assertEquals(listOf(Command.CHATS, Command.CALIBRATE), Commands.suggestions("/c"))
        assertEquals(Command.entries.size, Commands.suggestions("/").size)
    }

    @Test
    fun noSuggestionsForExactWordOrText() {
        assertEquals(emptyList<Command>(), Commands.suggestions("/help"))
        assertEquals(emptyList<Command>(), Commands.suggestions("hello"))
        assertEquals(emptyList<Command>(), Commands.suggestions("/new now"))
    }
}
