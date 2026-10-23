package app.ghost.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class Repository(private val dao: ChatDao) {
    val chatRows: Flow<List<ChatRow>> = dao.chatRows()

    suspend fun latestConversation(): Conversation? = dao.latestConversation()

    suspend fun conversation(id: Long): Conversation? = dao.conversation(id)

    suspend fun messages(conversationId: Long): List<Message> = dao.messages(conversationId)

    suspend fun createConversation(firstUserText: String): Conversation {
        val now = System.currentTimeMillis()
        val title = firstUserText.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim().take(40)
        val conversation = Conversation(title = title.ifEmpty { "image" }, createdAt = now, updatedAt = now)
        return conversation.copy(id = dao.insertConversation(conversation))
    }

    suspend fun addMessage(message: Message): Message {
        val id = dao.insertMessage(message)
        dao.touchConversation(message.conversationId, message.createdAt)
        return message.copy(id = id)
    }

    suspend fun renameConversation(id: Long, title: String) = dao.setTitle(id, title.trim().take(TITLE_MAX), true)

    suspend fun setGeneratedTitle(id: Long, title: String) = dao.setGeneratedTitle(id, title.take(TITLE_MAX))

    suspend fun setPinned(id: Long, pinned: Boolean) = dao.setPinned(id, pinned)

    suspend fun deleteConversation(id: Long) {
        val paths = dao.imagePaths(id)
        dao.deleteConversation(id)
        withContext(Dispatchers.IO) { paths.forEach { File(it).delete() } }
    }

    private companion object {
        const val TITLE_MAX = 60
    }
}
