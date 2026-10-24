package app.ghost.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query(
        """
        SELECT c.id, c.title, c.updatedAt, c.pinned,
            (SELECT COUNT(*) FROM messages m WHERE m.conversationId = c.id) AS messageCount
        FROM conversations c ORDER BY c.pinned DESC, c.updatedAt DESC
        """
    )
    fun chatRows(): Flow<List<ChatRow>>

    @Query("UPDATE conversations SET title = :title, titleEdited = :edited WHERE id = :id")
    suspend fun setTitle(id: Long, title: String, edited: Boolean)

    @Query("UPDATE conversations SET title = :title WHERE id = :id AND titleEdited = 0")
    suspend fun setGeneratedTitle(id: Long, title: String)

    @Query("UPDATE conversations SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

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

    @Query("SELECT docPath FROM messages WHERE conversationId = :conversationId AND docPath IS NOT NULL")
    suspend fun docPaths(conversationId: Long): List<String>

    @Insert
    suspend fun insertMessage(message: Message): Long

    @Query("UPDATE messages SET tokens = :tokens WHERE id = :id")
    suspend fun setTokens(id: Long, tokens: Int)

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteMessages(ids: List<Long>)

    @Query("UPDATE conversations SET summary = :summary, summaryUpTo = :upTo WHERE id = :id")
    suspend fun setSummary(id: Long, summary: String, upTo: Long)
}

@Database(entities = [Conversation::class, Message::class], version = 2, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN summary TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversations ADD COLUMN summaryUpTo INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN docPath TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN docName TEXT")
            }
        }

        fun create(context: Context): Db =
            Room.databaseBuilder(context, Db::class.java, "ghost.db").addMigrations(MIGRATION_1_2).build()
    }
}
