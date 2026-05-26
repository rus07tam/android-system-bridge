package org.ruject.gateway.services

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.ruject.gateway.data.repository.GatewayRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class NotificationModel(
    val id: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val key: String
)

class GatewayNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var repository: GatewayRepository

    override fun onCreate() {
        super.onCreate()
        repository = GatewayRepository(applicationContext)
        instance = this
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val extras = sbn.notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val pkg = sbn.packageName

        if (title.isNotBlank() || text.isNotBlank()) {
            scope.launch {
                repository.saveNotification(pkg, title, text)
            }
        }
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun getActiveNotificationModels(): List<NotificationModel> {
        val results = mutableListOf<NotificationModel>()
        try {
            val notifications = activeNotifications
            if (notifications != null) {
                for (sbn in notifications) {
                    val extras = sbn.notification.extras
                    val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                    val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                    val key = sbn.key
                    results.add(
                        NotificationModel(
                            id = sbn.id.toString(),
                            packageName = sbn.packageName,
                            title = title,
                            text = text,
                            postTime = sbn.postTime,
                            key = key
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return results
    }

    fun dismissNotificationByKey(key: String) {
        cancelNotification(key)
    }

    fun replyToNotification(key: String, replyText: String): Boolean {
        try {
            val notifications = activeNotifications ?: return false
            val sbn = notifications.firstOrNull { it.key == key } ?: return false
            val actions = sbn.notification.actions ?: return false

            for (action in actions) {
                val remoteInputs = action.remoteInputs ?: continue
                for (input in remoteInputs) {
                    val bundle = Bundle()
                    bundle.putCharSequence(input.resultKey, replyText)

                    val intent = Intent()
                    RemoteInput.addResultsToIntent(arrayOf(input), intent, bundle)

                    // Retrieve the dynamic remote input reply source matching protocol
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_CHOICE)
                    }

                    action.actionIntent.send(applicationContext, 0, intent)
                    return true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    companion object {
        var instance: GatewayNotificationListenerService? = null
            private set
    }
}
