package org.ruject.gateway.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import org.ruject.gateway.data.database.AppDatabase
import org.ruject.gateway.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

class GatewayContentProvider : ContentProvider() {

    private lateinit var database: AppDatabase

    companion object {
        const val AUTHORITY = "org.ruject.gateway.provider"
        private const val CODE_LOGS = 1
        private const val CODE_CLIPBOARD = 2
        private const val CODE_NOTIFICATIONS = 3

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "logs", CODE_LOGS)
            addURI(AUTHORITY, "clipboard", CODE_CLIPBOARD)
            addURI(AUTHORITY, "notifications", CODE_NOTIFICATIONS)
        }
    }

    override fun onCreate(): Boolean {
        database = AppDatabase.getDatabase(context ?: return false)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null
        return when (uriMatcher.match(uri)) {
            CODE_LOGS -> {
                val cursor = MatrixCursor(arrayOf("id", "timestamp", "api_method", "request_payload", "response_payload", "status"))
                runBlocking {
                    // Synchronously load the latest logs to populate the MatrixCursor
                    val db = AppDatabase.getDatabase(ctx)
                    try {
                        val queryCursor = db.openHelper.readableDatabase.query(
                            "SELECT * FROM api_logs ORDER BY timestamp DESC LIMIT 50"
                        )
                        queryCursor.use { qCursor ->
                            while (qCursor.moveToNext()) {
                                cursor.addRow(
                                    arrayOf(
                                        qCursor.getLong(qCursor.getColumnIndexOrThrow("id")),
                                        qCursor.getLong(qCursor.getColumnIndexOrThrow("timestamp")),
                                        qCursor.getString(qCursor.getColumnIndexOrThrow("apiMethod")),
                                        qCursor.getString(qCursor.getColumnIndexOrThrow("requestPayload")),
                                        qCursor.getString(qCursor.getColumnIndexOrThrow("responsePayload")),
                                        qCursor.getString(qCursor.getColumnIndexOrThrow("status"))
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                cursor
            }
            CODE_CLIPBOARD -> {
                val cursor = MatrixCursor(arrayOf("id", "text", "timestamp"))
                runBlocking {
                    val db = AppDatabase.getDatabase(ctx)
                    try {
                        val queryCursor = db.openHelper.readableDatabase.query(
                            "SELECT * FROM clipboard_history ORDER BY timestamp DESC LIMIT 50"
                        )
                        queryCursor.use { qCursor ->
                            while (qCursor.moveToNext()) {
                                cursor.addRow(
                                    arrayOf(
                                        qCursor.getLong(qCursor.getColumnIndexOrThrow("id")),
                                        qCursor.getString(qCursor.getColumnIndexOrThrow("text")),
                                        qCursor.getLong(qCursor.getColumnIndexOrThrow("timestamp"))
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                cursor
            }
            CODE_NOTIFICATIONS -> {
                val cursor = MatrixCursor(arrayOf("id", "package_name", "title", "text", "timestamp"))
                runBlocking {
                    val db = AppDatabase.getDatabase(ctx)
                    try {
                        val queryCursor = db.openHelper.readableDatabase.query(
                            "SELECT * FROM notification_history ORDER BY timestamp DESC LIMIT 50"
                        )
                        queryCursor.use { qCursor ->
                            while (qCursor.moveToNext()) {
                                cursor.addRow(
                                    arrayOf(
                                        qCursor.getLong(qCursor.getColumnIndexOrThrow("id")),
                                        qCursor.getString(qCursor.getColumnIndexOrThrow("packageName")),
                                        qCursor.getString(qCursor.getColumnIndexOrThrow("title")),
                                        qCursor.getString(qCursor.getColumnIndexOrThrow("text")),
                                        qCursor.getLong(qCursor.getColumnIndexOrThrow("timestamp"))
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                cursor
            }
            else -> null
        }
    }

    override fun getType(uri: Uri): String? {
        return when (uriMatcher.match(uri)) {
            CODE_LOGS -> "vnd.android.cursor.dir/$AUTHORITY.logs"
            CODE_CLIPBOARD -> "vnd.android.cursor.dir/$AUTHORITY.clipboard"
            CODE_NOTIFICATIONS -> "vnd.android.cursor.dir/$AUTHORITY.notifications"
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    // Provide high performance direct call method execution over Provider IPC!
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        val responseBundle = Bundle()
        try {
            // Category matches method, Action matches arg, parameters in extra bundle
            val category = method
            val action = arg ?: "read"
            val paramsMap = mutableMapOf<String, Any?>()

            extras?.keySet()?.forEach { key ->
                paramsMap[key] = extras.get(key)
            }

            // Execute
            val result = ToolRegistry.execute(ctx, category, action, paramsMap)
            responseBundle.putString("response", result.toString())
            responseBundle.putString("status", result.optString("status"))
        } catch (e: Exception) {
            responseBundle.putString("status", "error")
            responseBundle.putString("message", e.localizedMessage)
        }
        return responseBundle
    }
}
