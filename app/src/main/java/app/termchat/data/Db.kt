package app.termchat.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query(
        """
        SELECT c.id, c.title, c.updatedAt,
            (SELECT COUNT(*) FROM messages m WHERE m.conversationId = c.id) AS messageCount
        FROM conversations c ORDER BY c.updatedAt DESC
        """
    )
    fun chatRows(): Flow<List<ChatRow>>

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestConversation(): Conversation?

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun conversation(id: Long): Conversation?

    @Insert
    suspend fun insertConversation(conversation: Conversation): Long

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchConversation(id: Long, updatedAt: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt, id")
    suspend fun messages(conversationId: Long): List<Message>

    @Query("SELECT imagePath FROM messages WHERE conversationId = :conversationId AND imagePath IS NOT NULL")
    suspend fun imagePaths(conversationId: Long): List<String>

    @Insert
    suspend fun insertMessage(message: Message): Long
}

@Database(entities = [Conversation::class, Message::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        fun create(context: Context): Db =
            Room.databaseBuilder(context, Db::class.java, "termchat.db").build()
    }
}
