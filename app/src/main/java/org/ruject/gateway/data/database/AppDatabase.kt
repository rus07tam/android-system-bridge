package org.ruject.gateway.data.database

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    // Config Entity
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfig(config: ConfigEntity)

    @Query("SELECT * FROM config WHERE `key` = :key")
    suspend fun getConfig(key: String): ConfigEntity?

    @Query("SELECT * FROM config")
    fun getAllConfigsFlow(): Flow<List<ConfigEntity>>

    // Logs Entity
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: LogEntity)

    @Query("SELECT * FROM api_logs ORDER BY timestamp DESC LIMIT 200")
    fun getAllLogs(): Flow<List<LogEntity>>

    @Query("DELETE FROM api_logs")
    suspend fun clearLogs()

    // Clipboard History
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClipboard(clip: ClipboardHistory)

    @Query("SELECT * FROM clipboard_history ORDER BY timestamp DESC LIMIT 100")
    fun getAllClipboard(): Flow<List<ClipboardHistory>>

    @Query("DELETE FROM clipboard_history")
    suspend fun clearClipboard()

    // Notification History
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notif: NotificationHistory)

    @Query("SELECT * FROM notification_history ORDER BY timestamp DESC LIMIT 100")
    fun getAllNotifications(): Flow<List<NotificationHistory>>

    @Query("DELETE FROM notification_history")
    suspend fun clearNotifications()
}

@Database(
    entities = [ConfigEntity::class, LogEntity::class, ClipboardHistory::class, NotificationHistory::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "gateway_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
