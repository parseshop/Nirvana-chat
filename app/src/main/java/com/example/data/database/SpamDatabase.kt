package com.example.data.database

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "spam_rules")
data class SpamRule(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val pattern: String,
    val type: String, // "KEYWORD" or "SENDER"
    val isBlacklist: Boolean = true // true = spam, false = whitelist/ham
)

@Entity(tableName = "message_classifications")
data class MessageClassification(
    @PrimaryKey val sender: String,
    val isSpam: Boolean,
    val userOverridden: Boolean = false
)

@Entity(tableName = "saved_messages")
data class SavedMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sender: String,
    val senderName: String?,
    val body: String,
    val timestamp: Long, // original message timestamp
    val savedAt: Long = System.currentTimeMillis()
)

@Dao
interface SpamRuleDao {
    @Query("SELECT * FROM spam_rules")
    fun getAllRulesFlow(): Flow<List<SpamRule>>

    @Query("SELECT * FROM spam_rules")
    suspend fun getAllRules(): List<SpamRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: SpamRule)

    @Delete
    suspend fun deleteRule(rule: SpamRule)

    @Query("DELETE FROM spam_rules WHERE pattern = :pattern")
    suspend fun deleteRuleByPattern(pattern: String)
}

@Dao
interface MessageClassificationDao {
    @Query("SELECT * FROM message_classifications")
    fun getAllClassificationsFlow(): Flow<List<MessageClassification>>

    @Query("SELECT * FROM message_classifications")
    suspend fun getAllClassifications(): List<MessageClassification>

    @Query("SELECT * FROM message_classifications WHERE sender = :sender")
    suspend fun getClassificationForSender(sender: String): MessageClassification?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClassification(classification: MessageClassification)

    @Query("DELETE FROM message_classifications")
    suspend fun clearAll()
}

@Dao
interface SavedMessageDao {
    @Query("SELECT * FROM saved_messages ORDER BY savedAt DESC")
    fun getAllSavedMessagesFlow(): Flow<List<SavedMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSavedMessage(savedMessage: SavedMessage)

    @Query("DELETE FROM saved_messages WHERE id = :id")
    suspend fun deleteSavedMessageById(id: Long)

    @Query("DELETE FROM saved_messages")
    suspend fun clearAll()
}

@Database(entities = [SpamRule::class, MessageClassification::class, SavedMessage::class], version = 2, exportSchema = false)
abstract class SpamDatabase : RoomDatabase() {
    abstract fun spamRuleDao(): SpamRuleDao
    abstract fun classificationDao(): MessageClassificationDao
    abstract fun savedMessageDao(): SavedMessageDao

    companion object {
        @Volatile
        private var INSTANCE: SpamDatabase? = null

        fun getDatabase(context: Context): SpamDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SpamDatabase::class.java,
                    "nirvana_spam_db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
