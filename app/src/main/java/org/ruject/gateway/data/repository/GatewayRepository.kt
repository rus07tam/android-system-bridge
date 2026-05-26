package org.ruject.gateway.data.repository

import android.content.Context
import org.ruject.gateway.data.database.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class GatewayRepository(private val context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val dao = db.dao()

    // Config cache or values
    companion object {
        const val KEY_AUTH_TOKEN = "auth_token"
        const val KEY_PORT = "server_port"
        const val KEY_ALLOW_EXTERNAL = "allow_external"
        const val KEY_SERVER_ACTIVE = "server_active"
    }

    val logs: Flow<List<LogEntity>> = dao.getAllLogs()
    val clipboardHistory: Flow<List<ClipboardHistory>> = dao.getAllClipboard()
    val notificationHistory: Flow<List<NotificationHistory>> = dao.getAllNotifications()

    suspend fun getAuthToken(): String {
        val config = dao.getConfig(KEY_AUTH_TOKEN)
        if (config == null || config.value.isBlank()) {
            val newToken = UUID.randomUUID().toString().substring(0, 16)
            dao.insertConfig(ConfigEntity(KEY_AUTH_TOKEN, newToken))
            return newToken
        }
        return config.value
    }

    suspend fun setAuthToken(token: String) {
        dao.insertConfig(ConfigEntity(KEY_AUTH_TOKEN, token))
    }

    suspend fun getServerPort(): Int {
        val config = dao.getConfig(KEY_PORT)
        return config?.value?.toIntOrNull() ?: 8080
    }

    suspend fun setServerPort(port: Int) {
        dao.insertConfig(ConfigEntity(KEY_PORT, port.toString()))
    }

    suspend fun getAllowExternal(): Boolean {
        val config = dao.getConfig(KEY_ALLOW_EXTERNAL)
        return config?.value?.toBoolean() ?: false
    }

    suspend fun setAllowExternal(allow: Boolean) {
        dao.insertConfig(ConfigEntity(KEY_ALLOW_EXTERNAL, allow.toString()))
    }

    suspend fun getServerActive(): Boolean {
        val config = dao.getConfig(KEY_SERVER_ACTIVE)
        return config?.value?.toBoolean() ?: false
    }

    suspend fun setServerActive(active: Boolean) {
        dao.insertConfig(ConfigEntity(KEY_SERVER_ACTIVE, active.toString()))
    }

    suspend fun logApiCall(method: String, request: String, response: String, status: String) {
        dao.insertLog(
            LogEntity(
                apiMethod = method,
                requestPayload = request,
                responsePayload = response,
                status = status
            )
        )
    }

    suspend fun saveClipboard(text: String) {
        dao.insertClipboard(ClipboardHistory(text = text))
    }

    suspend fun saveNotification(packageName: String, title: String, text: String) {
        dao.insertNotification(
            NotificationHistory(
                packageName = packageName,
                title = title,
                text = text
            )
        )
    }

    suspend fun clearLogs() = dao.clearLogs()
    suspend fun clearClipboard() = dao.clearClipboard()
    suspend fun clearNotifications() = dao.clearNotifications()
}
