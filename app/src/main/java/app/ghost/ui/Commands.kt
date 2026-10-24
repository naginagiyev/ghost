package app.ghost.ui

enum class Command(val word: String, val help: String) {
    NEW("/new", "start a new conversation"),
    CHATS("/chats", "open the conversation list"),
    MODEL("/model", "switch between models"),
    IMPORT("/import", "import a model file"),
    STATS("/stats", "device, model, memory and last run"),
    UNLOAD("/unload", "unload the model"),
    RELOAD("/reload", "reload the model"),
    DELETE("/delete", "delete this conversation"),
    HELP("/help", "list commands"),
}

sealed interface Input {
    data class Run(val command: Command) : Input
    data class Unknown(val word: String) : Input
    data class Text(val text: String) : Input
}

object Commands {
    fun parse(raw: String): Input {
        val text = raw.trim()
        if (!text.startsWith("/") || text.contains('\n')) return Input.Text(text)
        val word = text.substringBefore(' ').lowercase()
        val command = Command.entries.firstOrNull { it.word == word } ?: return Input.Unknown(word)
        return Input.Run(command)
    }

    fun suggestions(raw: String): List<Command> {
        if (!raw.startsWith("/") || raw.any { it.isWhitespace() }) return emptyList()
        val prefix = raw.lowercase()
        return Command.entries.filter { it.word.startsWith(prefix) && it.word != prefix }
    }

    fun helpLines(): List<String> = Command.entries.map { it.word.padEnd(11) + it.help }
}
