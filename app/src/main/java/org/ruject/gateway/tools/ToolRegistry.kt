package org.ruject.gateway.tools

import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.app.ActivityManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.*
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import android.view.accessibility.AccessibilityNodeInfo
import org.ruject.gateway.services.GatewayAccessibilityService
import org.ruject.gateway.services.GatewayNotificationListenerService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object ToolRegistry {

    fun execute(context: Context, category: String, action: String, params: Map<String, Any?>): JSONObject {
        val result = JSONObject()
        try {
            when (category.lowercase()) {
                "contacts" -> handleContacts(context, action, params, result)
                "calendar" -> handleCalendar(context, action, params, result)
                "alarms" -> handleAlarms(context, action, params, result)
                "sms" -> handleSms(context, action, params, result)
                "notifications" -> handleNotifications(context, action, params, result)
                "clipboard" -> handleClipboard(context, action, params, result)
                "accessibility" -> handleAccessibility(context, action, params, result)
                "usagestats" -> handleUsageStats(context, action, params, result)
                "intents" -> handleIntents(context, action, params, result)
                "filesystem" -> handleFilesystem(context, action, params, result)
                "deviceinfo" -> handleDeviceInfo(context, action, params, result)
                "media" -> handleMedia(context, action, params, result)
                else -> {
                    result.put("status", "error")
                    result.put("message", "Unknown tool category: $category")
                }
            }
        } catch (e: Exception) {
            result.put("status", "error")
            result.put("message", "Execution error: ${e.localizedMessage}")
        }
        return result
    }

    private fun handleContacts(context: Context, action: String, params: Map<String, Any?>, result: JSONObject) {
        val resolver: ContentResolver = context.contentResolver
        when (action.lowercase()) {
            "read", "search" -> {
                val query = params["query"] as? String ?: ""
                val array = JSONArray()
                val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID
                )
                val selection = if (query.isNotBlank()) {
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
                } else null
                val selectionArgs = if (query.isNotBlank()) {
                    arrayOf("%$query%")
                } else null

                val cursor = resolver.query(uri, projection, selection, selectionArgs, null)
                cursor?.use {
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                    while (cursor.moveToNext()) {
                        val contact = JSONObject()
                        if (nameIdx >= 0) contact.put("name", cursor.getString(nameIdx))
                        if (numIdx >= 0) contact.put("number", cursor.getString(numIdx))
                        if (idIdx >= 0) contact.put("id", cursor.getLong(idIdx))
                        array.put(contact)
                    }
                }
                result.put("status", "success")
                result.put("contacts", array)
            }
            "create" -> {
                val name = params["name"] as? String ?: throw IllegalArgumentException("Missing parameter 'name'")
                val phone = params["phone"] as? String ?: ""
                
                val ops = ArrayList<ContentProviderOperation>()
                val rawContactInsertIndex = ops.size
                
                ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build())
                
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build())
                
                if (phone.isNotBlank()) {
                    ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                        .build())
                }

                resolver.applyBatch(ContactsContract.AUTHORITY, ops)
                result.put("status", "success")
                result.put("message", "Contact '$name' created successfully.")
            }
            "delete" -> {
                val name = params["name"] as? String ?: throw IllegalArgumentException("Missing parameter 'name'")
                val uri = ContactsContract.RawContacts.CONTENT_URI
                val selection = "${ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY} = ?"
                val selectionArgs = arrayOf(name)
                val deleted = resolver.delete(uri, selection, selectionArgs)
                result.put("status", "success")
                result.put("deleted_count", deleted)
            }
            else -> throw IllegalArgumentException("Unknown contacts action: $action")
        }
    }

    private fun handleCalendar(context: Context, action: String, params: Map<String, Any?>, result: JSONObject) {
        val resolver: ContentResolver = context.contentResolver
        when (action.lowercase()) {
            "list" -> {
                val array = JSONArray()
                val uri = CalendarContract.Events.CONTENT_URI
                val projection = arrayOf(
                    CalendarContract.Events._ID,
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DESCRIPTION,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND
                )
                val cursor = resolver.query(uri, projection, null, null, "${CalendarContract.Events.DTSTART} DESC LIMIT 50")
                cursor?.use {
                    val idIdx = cursor.getColumnIndex(CalendarContract.Events._ID)
                    val titleIdx = cursor.getColumnIndex(CalendarContract.Events.TITLE)
                    val descIdx = cursor.getColumnIndex(CalendarContract.Events.DESCRIPTION)
                    val startIdx = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
                    val endIdx = cursor.getColumnIndex(CalendarContract.Events.DTEND)
                    while (cursor.moveToNext()) {
                        val event = JSONObject()
                        if (idIdx >= 0) event.put("id", cursor.getLong(idIdx))
                        if (titleIdx >= 0) event.put("title", cursor.getString(titleIdx))
                        if (descIdx >= 0) event.put("description", cursor.getString(descIdx))
                        if (startIdx >= 0) event.put("start", cursor.getLong(startIdx))
                        if (endIdx >= 0) event.put("end", cursor.getLong(endIdx))
                        array.put(event)
                    }
                }
                result.put("status", "success")
                result.put("events", array)
            }
            "create" -> {
                val title = params["title"] as? String ?: throw IllegalArgumentException("Missing 'title'")
                val description = params["description"] as? String ?: ""
                val start = (params["start"] as? Number)?.toLong() ?: System.currentTimeMillis()
                val end = (params["end"] as? Number)?.toLong() ?: (start + 3600000)

                val values = ContentValues().apply {
                    put(CalendarContract.Events.TITLE, title)
                    put(CalendarContract.Events.DESCRIPTION, description)
                    put(CalendarContract.Events.DTSTART, start)
                    put(CalendarContract.Events.DTEND, end)
                    put(CalendarContract.Events.CALENDAR_ID, 1) // Default local calendar
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                }
                val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
                result.put("status", "success")
                result.put("event_id", uri?.lastPathSegment)
            }
            "delete" -> {
                val id = (params["id"] as? Number)?.toLong() ?: throw IllegalArgumentException("Missing 'id'")
                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
                val deleted = resolver.delete(uri, null, null)
                result.put("status", "success")
                result.put("deleted_count", deleted)
            }
            else -> throw IllegalArgumentException("Unknown calendar action: $action")
        }
    }

    private fun handleAlarms(context: Context, action: String, params: Map<String, Any?>, result: JSONObject) {
        when (action.lowercase()) {
            "create" -> {
                val hour = (params["hour"] as? Number)?.toInt() ?: throw IllegalArgumentException("Missing 'hour'")
                val minutes = (params["minutes"] as? Number)?.toInt() ?: throw IllegalArgumentException("Missing 'minutes'")
                val message = params["message"] as? String ?: "API Alarm"
                
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minutes)
                    putExtra(AlarmClock.EXTRA_MESSAGE, message)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                result.put("status", "success")
                result.put("message", "Alarm requested at $hour:$minutes with message '$message'")
            }
            "list" -> {
                // Since the Android SDK does not provide direct listing of system level alarms through AlarmManager,
                // we return a helper notice.
                result.put("status", "success")
                result.put("info", "Standard Android Security restricts reading external alarms. Trigger intents to modify Alarms instead.")
            }
            else -> throw IllegalArgumentException("Unknown alarms action: $action")
        }
    }

    private fun handleSms(context: Context, action: String, params: Map<String, Any?>, result: JSONObject) {
        val resolver: ContentResolver = context.contentResolver
        when (action.lowercase()) {
            "send" -> {
                val recipient = params["recipient"] as? String ?: throw IllegalArgumentException("Missing 'recipient'")
                val message = params["message"] as? String ?: throw IllegalArgumentException("Missing 'message'")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val smsManager: SmsManager = context.getSystemService(SmsManager::class.java)
                    smsManager.sendTextMessage(recipient, null, message, null, null)
                } else {
                    @Suppress("DEPRECATION")
                    val smsManager: SmsManager = SmsManager.getDefault()
                    smsManager.sendTextMessage(recipient, null, message, null, null)
                }
                result.put("status", "success")
                result.put("message", "SMS sent successfully to $recipient")
            }
            "read" -> {
                val limit = (params["limit"] as? Number)?.toInt() ?: 20
                val array = JSONArray()
                val cursor = resolver.query(
                    Uri.parse("content://sms/inbox"),
                    arrayOf("_id", "address", "body", "date"),
                    null, null, "date DESC LIMIT $limit"
                )
                cursor?.use {
                    val idIdx = cursor.getColumnIndex("_id")
                    val addrIdx = cursor.getColumnIndex("address")
                    val bodyIdx = cursor.getColumnIndex("body")
                    val dateIdx = cursor.getColumnIndex("date")
                    while (cursor.moveToNext()) {
                        val sms = JSONObject()
                        if (idIdx >= 0) sms.put("id", cursor.getLong(idIdx))
                        if (addrIdx >= 0) sms.put("sender", cursor.getString(addrIdx))
                        if (bodyIdx >= 0) sms.put("body", cursor.getString(bodyIdx))
                        if (dateIdx >= 0) sms.put("timestamp", cursor.getLong(dateIdx))
                        array.put(sms)
                    }
                }
                result.put("status", "success")
                result.put("messages", array)
            }
            "calls" -> {
                val limit = (params["limit"] as? Number)?.toInt() ?: 10
                val array = JSONArray()
                val cursor = resolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                    null, null, "${CallLog.Calls.DATE} DESC LIMIT $limit"
                )
                cursor?.use {
                    val idIdx = cursor.getColumnIndex(CallLog.Calls._ID)
                    val numIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                    val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
                    val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
                    val durIdx = cursor.getColumnIndex(CallLog.Calls.DURATION)
                    while (cursor.moveToNext()) {
                        val call = JSONObject()
                        if (idIdx >= 0) call.put("id", cursor.getLong(idIdx))
                        if (numIdx >= 0) call.put("number", cursor.getString(numIdx))
                        if (typeIdx >= 0) {
                            val type = when (cursor.getInt(typeIdx)) {
                                CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                                CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                                CallLog.Calls.MISSED_TYPE -> "MISSED"
                                else -> "UNKNOWN"
                            }
                            call.put("type", type)
                        }
                        if (dateIdx >= 0) call.put("timestamp", cursor.getLong(dateIdx))
                        if (durIdx >= 0) call.put("duration", cursor.getLong(durIdx))
                        array.put(call)
                    }
                }
                result.put("status", "success")
                result.put("calls", array)
            }
            else -> throw IllegalArgumentException("Unknown SMS action: $action")
        }
    }

    private fun handleNotifications(context: Context, action: String, params: Map<String, Any?>, result: JSONObject) {
        val serviceInstance = GatewayNotificationListenerService.instance
        if (serviceInstance == null) {
            result.put("status", "error")
            result.put("message", "NotificationListenerService is not configured or enabled or not running. Ask the user to enable system access.")
            return
        }

        when (action.lowercase()) {
            "active" -> {
                val list = serviceInstance.getActiveNotificationModels()
                val array = JSONArray()
                list.forEach { notif ->
                    array.put(JSONObject().apply {
                        put("id", notif.id)
                        put("package", notif.packageName)
                        put("title", notif.title)
                        put("text", notif.text)
                        put("timestamp", notif.postTime)
                        put("key", notif.key)
                    })
                }
                result.put("status", "success")
                result.put("notifications", array)
            }
            "dismiss" -> {
                val key = params["key"] as? String ?: throw IllegalArgumentException("Missing 'key'")
                serviceInstance.dismissNotificationByKey(key)
                result.put("status", "success")
                result.put("message", "Dismissed notification with key $key")
            }
            "reply" -> {
                val key = params["key"] as? String ?: throw IllegalArgumentException("Missing 'key'")
                val text = params["text"] as? String ?: throw IllegalArgumentException("Missing 'text'")
                val success = serviceInstance.replyToNotification(key, text)
                if (success) {
                    result.put("status", "success")
                    result.put("message", "Reply posted successfully")
                } else {
                    result.put("status", "error")
                    result.put("message", "Could not find a secure quick reply action block for key $key")
                }
            }
            else -> throw IllegalArgumentException("Unknown notifications action: $action")
        }
    }

    private fun handleClipboard(context: Context, action: String, params: Map<String, Any?>, result: JSONObject) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        when (action.lowercase()) {
            "read" -> {
                val clipData = clipboard.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val text = clipData.getItemAt(0).text?.toString() ?: ""
                    result.put("status", "success")
                    result.put("text", text)
                } else {
                    result.put("status", "success")
                    result.put("text", "")
                }
            }
            "write" -> {
                val text = params["text"] as? String ?: throw IllegalArgumentException("Missing parameter 'text'")
                val clip = ClipData.newPlainText("Gateway Clipboard", text)
                clipboard.setPrimaryClip(clip)
                result.put("status", "success")
                result.put("message", "Clipboard text captured.")
            }
            else -> throw IllegalArgumentException("Unknown clipboard action: $action")
        }
    }

    private fun handleAccessibility(context: Context, action: String, params: Map<String, Any?>, result: JSONObject) {
        val service = GatewayAccessibilityService.instance
        if (service == null) {
            result.put("status", "error")
            result.put("message", "Accessibility Service is not currently active. Enable in Accessibility Settings.")
            return
        }

        when (action.lowercase()) {
            "dump" -> {
                val root = service.rootInActiveWindow
                if (root == null) {
                    result.put("status", "error")
                    result.put("message", "No active window element found. Make sure the screen is unlocked.")
                    return
                }
                val nodeJson = dumpNode(root)
                result.put("status", "success")
                result.put("root", nodeJson)
            }
            "action" -> {
                val targetText = params["text"] as? String
                val nodeId = (params["node_id"] as? Number)?.toInt()
                val act = params["action"] as? String ?: "click"
                
                val root = service.rootInActiveWindow
                if (root == null) {
                    result.put("status", "error")
                    result.put("message", "No root node found in current active window")
                    return
                }

                val targetNode = if (targetText != null) {
                    findNodeByText(root, targetText)
                } else if (nodeId != null) {
                    findNodeById(root, nodeId)
                } else null

                if (targetNode == null) {
                    result.put("status", "error")
                    result.put("message", "Could not resolve node target.")
                    return
                }

                val composeAction = when (act.lowercase()) {
                    "click" -> targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    "scroll_forward" -> targetNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    "scroll_backward" -> targetNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    "focus" -> targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    else -> false
                }
                result.put("status", if (composeAction) "success" else "error")
                result.put("message", if (composeAction) "Action executed" else "Action failed to execute or target unsupported")
            }
            "global_action" -> {
                val act = params["action"] as? String ?: "back"
                val globalCode = when (act.lowercase()) {
                    "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                    "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                    "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                    "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                    "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                    else -> -1
                }
                if (globalCode != -1) {
                    val success = service.performGlobalAction(globalCode)
                    result.put("status", if (success) "success" else "error")
                } else {
                    result.put("status", "error")
                    result.put("message", "Unsupported global action: $act")
                }
            }
            else -> throw IllegalArgumentException("Unknown accessibility action: $action")
        }
    }

    private fun dumpNode(node: AccessibilityNodeInfo): JSONObject {
        val json = JSONObject()
        json.put("class", node.className?.toString() ?: "")
        json.put("text", node.text?.toString() ?: "")
        json.put("content_description", node.contentDescription?.toString() ?: "")
        json.put("clickable", node.isClickable)
        json.put("focusable", node.isFocusable)
        json.put("scrollable", node.isScrollable)
        json.put("bounds", node.toString().substringAfter("boundsInScreen: ").substringBefore(";"))
        json.put("node_id", node.hashCode())

        if (node.childCount > 0) {
            val children = JSONArray()
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    children.put(dumpNode(child))
                }
            }
            json.put("children", children)
        }
        return json
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString()?.contains(text, ignoreCase = true) == true ||
            node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByText(child, text)
            if (found != null) return found
        }
        return null
    }

    private fun findNodeById(node: AccessibilityNodeInfo, id: Int): AccessibilityNodeInfo? {
        if (node.hashCode() == id) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeById(child, id)
            if (found != null) return found
        }
        return null
    }

    private fun handleUsageStats(context: Context, action: String, params: Map<String, Any?>, result: JSONObject) {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        when (action.lowercase()) {
            "list" -> {
                val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
                val array = JSONArray()
                if (stats != null) {
                    val sortedStats = stats.sortedByDescending { it.totalTimeInForeground }
                    sortedStats.take(15).forEach { stat ->
                        if (stat.totalTimeInForeground > 0) {
                            val item = JSONObject().apply {
                                put("package", stat.packageName)
                                put("foreground_time_ms", stat.totalTimeInForeground)
                                put("last_time_used", stat.lastTimeUsed)
                            }
                            array.put(item)
                        }
                    }
                }
                result.put("status", "success")
                result.put("usage_stats", array)
            }
            "foreground" -> {
                val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
                var foregroundApp = "Unknown"
                var lastActiveTime = 0L
                if (stats != null) {
                    for (stat in stats) {
                        if (stat.lastTimeUsed > lastActiveTime) {
                            lastActiveTime = stat.lastTimeUsed
                            foregroundApp = stat.packageName
                        }
                    }
                }
                result.put("status", "success")
                result.put("foreground_app", foregroundApp)
                result.put("last_active_time", lastActiveTime)
            }
            else -> throw IllegalArgumentException("Unknown usage stats action: $action")
        }
    }

    private fun handleIntents(context: Context, action: String, params: Map<String, Any?>, result: JSONObject) {
        val pm = context.packageManager
        when (action.lowercase()) {
            "launch" -> {
                val pack = params["package"] as? String ?: throw IllegalArgumentException("Missing 'package'")
                val intent = pm.getLaunchIntentForPackage(pack)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    result.put("status", "success")
                } else {
                    result.put("status", "error")
                    result.put("message", "Package '$pack' could not be resolved or launched.")
                }
            }
            "deeplink" -> {
                val url = params["url"] as? String ?: throw IllegalArgumentException("Missing parameter 'url'")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                result.put("status", "success")
            }
            "discovery" -> {
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                val array = JSONArray()
                for (app in apps) {
                    val appJson = JSONObject().apply {
                        put("package", app.packageName)
                        put("name", app.loadLabel(pm).toString())
                        put("enabled", app.enabled)
                        put("system", (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                    }
                    array.put(appJson)
                }
                result.put("status", "success")
                result.put("packages", array)
            }
            else -> throw IllegalArgumentException("Unknown intents action: $action")
        }
    }

    private fun handleFilesystem(context: Context, action: String, params: Map<String, Any?>, result: JSONObject) {
        val rootDir = context.filesDir
        when (action.lowercase()) {
            "list" -> {
                val array = JSONArray()
                rootDir.listFiles()?.forEach { file ->
                    array.put(JSONObject().apply {
                        put("name", file.name)
                        put("size", file.length())
                        put("is_dir", file.isDirectory)
                    })
                }
                result.put("status", "success")
                result.put("files", array)
            }
            "write" -> {
                val filename = params["filename"] as? String ?: throw IllegalArgumentException("Missing parameter 'filename'")
                val content = params["content"] as? String ?: ""
                val file = File(rootDir, filename)
                file.writeText(content)
                result.put("status", "success")
                result.put("message", "Successfully saved ${file.length()} bytes to internal files.")
            }
            "read" -> {
                val filename = params["filename"] as? String ?: throw IllegalArgumentException("Missing parameter 'filename'")
                val file = File(rootDir, filename)
                if (file.exists()) {
                    val text = file.readText()
                    result.put("status", "success")
                    result.put("content", text)
                } else {
                    result.put("status", "error")
                    result.put("message", "File '$filename' does not exist.")
                }
            }
            "delete" -> {
                val filename = params["filename"] as? String ?: throw IllegalArgumentException("Missing parameter 'filename'")
                val file = File(rootDir, filename)
                if (file.exists()) {
                    val deleted = file.delete()
                    result.put("status", if (deleted) "success" else "error")
                } else {
                    result.put("status", "error")
                    result.put("message", "File '$filename' not found.")
                }
            }
            else -> throw IllegalArgumentException("Unknown filesystem action: $action")
        }
    }

    private fun handleDeviceInfo(context: Context, action: String, params: Map<String, Any?>, result: JSONObject) {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            context.registerReceiver(null, filter)
        }
        val pct = batteryStatus?.let { intent ->
            val level: Int = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale: Int = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            level * 100 / scale.toFloat()
        } ?: -1f

        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)

        val statFs = StatFs(Environment.getDataDirectory().path)
        val availableBytes = statFs.availableBlocksLong * statFs.blockSizeLong
        val totalBytes = statFs.blockCountLong * statFs.blockSizeLong

        result.put("status", "success")
        result.put("device_model", Build.MODEL)
        result.put("sdk_version", Build.VERSION.SDK_INT)
        result.put("battery_percentage", pct)
        result.put("memory_total_bytes", memInfo.totalMem)
        result.put("memory_available_bytes", memInfo.availMem)
        result.put("storage_total_bytes", totalBytes)
        result.put("storage_available_bytes", availableBytes)

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var networkType = "NONE"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val netCapabilities = cm.getNetworkCapabilities(cm.activeNetwork)
            if (netCapabilities != null) {
                if (netCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    networkType = "WIFI"
                } else if (netCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    networkType = "CELLULAR"
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val activeNet = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            if (activeNet != null && activeNet.isConnected) {
                @Suppress("DEPRECATION")
                networkType = activeNet.typeName
            }
        }
        result.put("network_type", networkType)
    }

    private fun handleMedia(context: Context, action: String, params: Map<String, Any?>, result: JSONObject) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (action.lowercase()) {
            "mute" -> {
                val shouldMute = params["mute"] as? Boolean ?: true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (shouldMute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.setStreamMute(AudioManager.STREAM_MUSIC, shouldMute)
                }
                result.put("status", "success")
            }
            "volume" -> {
                val volume = (params["volume"] as? Number)?.toInt() ?: throw IllegalArgumentException("Missing parameter 'volume' (0 to max)")
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val target = volume.coerceIn(0, maxVolume)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
                result.put("status", "success")
                result.put("volume", target)
                result.put("max_volume", maxVolume)
            }
            "media_key" -> {
                val code = (params["key_code"] as? Number)?.toInt() ?: throw IllegalArgumentException("Missing parameter 'key_code' (e.g. 85 for Play/Pause, 87 Next, 88 Previous)")
                val downIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                    putExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
                }
                val upIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                    putExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
                }
                context.sendOrderedBroadcast(downIntent, null)
                context.sendOrderedBroadcast(upIntent, null)
                result.put("status", "success")
            }
            else -> throw IllegalArgumentException("Unknown media action: $action")
        }
    }
}
