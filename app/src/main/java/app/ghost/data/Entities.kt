package app.ghost.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val titleEdited: Boolean = false,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId", "createdAt")],
)
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val text: String,
    val imagePath: String?,
    val status: String,
    val createdAt: Long,
    val genMs: Long?,
    val tokens: Int?,
)

data class ChatRow(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val pinned: Boolean,
    val messageCount: Int,
)

object MessageRole {
    const val USER = "user"
    const val ASSISTANT = "assistant"
}

object MessageStatus {
    const val DONE = "done"
    const val INTERRUPTED = "interrupted"
    const val ERROR = "error"
}
