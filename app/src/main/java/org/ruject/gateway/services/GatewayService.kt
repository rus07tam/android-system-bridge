package org.ruject.gateway.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Base64
import androidx.core.app.NotificationCompat
import org.ruject.gateway.MainActivity
import org.ruject.gateway.R
import org.ruject.gateway.data.repository.GatewayRepository
import org.ruject.gateway.tools.ToolRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class GatewayService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private lateinit var repository: GatewayRepository
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    // Set of active WebSocket connections
    private val wsConnections = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val wsStreams = ConcurrentHashMap<Socket, OutputStream>()

    // Clipboard monitoring list
    private var clipboardManager: ClipboardManager? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val clip = clipboardManager?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: ""
            if (text.isNotBlank()) {
                scope.launch {
                    repository.saveClipboard(text)
                    broadcastWsEvent("clipboard_changed", JSONObject().apply {
                        put("text", text)
                    })
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = GatewayRepository(applicationContext)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipListener)

        createNotificationChannel()
        startForeground(101, createStatusNotification("Server is starting..."))

        // Register phone state listener
        setupPhoneStateListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startServer()
        return START_STICKY
    }

    private fun startServer() {
        if (serverJob?.isActive == true) return

        serverJob = scope.launch(Dispatchers.IO) {
            val port = repository.getServerPort()
            val allowExternal = repository.getAllowExternal()
            val token = repository.getAuthToken()

            try {
                // If allowExternal is false, bind only to localhost (127.0.0.1) for isolation
                serverSocket = if (allowExternal) {
                    ServerSocket(port)
                } else {
                    ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
                }

                updateForegroundNotification("Listening on ${if (allowExternal) "0.0.0.0" else "127.0.0.1"}:$port")

                while (isActive) {
                    val socket = serverSocket?.accept() ?: break
                    launch(Dispatchers.IO) {
                        handleClientConnection(socket, token, allowExternal)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                updateForegroundNotification("Server Error: ${e.localizedMessage}")
            }
        }
    }

    private suspend fun handleClientConnection(socket: Socket, token: String, allowExternal: Boolean) {
        val inputStream = socket.getInputStream()
        val outputStream = socket.getOutputStream()

        try {
            // Read HTTP headers
            val reader = inputStream.bufferedReader()
            val requestLine = reader.readLine() ?: return
            val requestParts = requestLine.split(" ")
            if (requestParts.size < 3) return

            val method = requestParts[0]
            val path = requestParts[1]

            // Read all headers
            val headers = mutableMapOf<String, String>()
            var line: String? = reader.readLine()
            while (!line.isNullOrBlank()) {
                val idx = line.indexOf(":")
                if (idx != -1) {
                    val k = line.substring(0, idx).trim().lowercase()
                    val v = line.substring(idx + 1).trim()
                    headers[k] = v
                }
                line = reader.readLine()
            }

            // Check authorization token
            var isAuthorized = !allowExternal // Localhost-bound doesn't strictly force token unless needed, but let's check it for maximum safety.
            
            // Extract token from Auth Header or URL parameters
            val authHeader = headers["authorization"]
            val queryToken = if (path.contains("?")) {
                path.substringAfter("?", "").split("&").firstOrNull { it.startsWith("token=") }?.substringAfter("token=")
            } else null

            val receivedToken = authHeader?.substringAfter("Bearer ") ?: queryToken

            if (receivedToken == token) {
                isAuthorized = true
            }

            // If we are looking on localhost but auth is specified, or we are listening externally
            if (!isAuthorized) {
                sendHttpError(outputStream, 401, "Unauthorized - Missing or invalid Bearer security token.")
                socket.close()
                return
            }

            // Check for WebSocket Upgrade
            if (headers["upgrade"] == "websocket" && headers["connection"]?.contains("upgrade", ignoreCase = true) == true) {
                val secKey = headers["sec-websocket-key"]
                if (secKey != null) {
                    performWebSocketHandshake(outputStream, secKey)
                    wsConnections.add(socket)
                    wsStreams[socket] = outputStream
                    handleWebSocketSession(socket, inputStream, outputStream)
                    return
                }
            }

            // Handle Standard REST endpoint
            if (method.equals("POST", ignoreCase = true)) {
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                val bodyBuilder = StringBuilder()
                if (contentLength > 0) {
                    val buffer = CharArray(contentLength)
                    var totalRead = 0
                    while (totalRead < contentLength) {
                        val numRead = reader.read(buffer, totalRead, contentLength - totalRead)
                        if (numRead == -1) break
                        totalRead += numRead
                    }
                    bodyBuilder.append(buffer, 0, totalRead)
                }

                val bodyText = bodyBuilder.toString()
                
                // Route endpoints POST /tools/<category>.<action>
                if (path.startsWith("/tools/")) {
                    val target = path.substringAfter("/tools/")
                    val category = target.substringBefore(".", "deviceinfo")
                    val action = target.substringAfter(".", "read")

                    val params = parseJsonToMap(bodyText)
                    val result = ToolRegistry.execute(applicationContext, category, action, params)
                    
                    // Log call to local database
                    repository.logApiCall(
                        method = "$category.$action",
                        request = bodyText,
                        response = result.toString(),
                        status = if (result.optString("status") == "success") "SUCCESS" else "ERROR"
                    )

                    sendHttpResponse(outputStream, 200, "application/json", result.toString())
                } else {
                    sendHttpError(outputStream, 404, "Not Found. Use /tools/category.action format.")
                }
            } else if (method.equals("GET", ignoreCase = true)) {
                if (path == "/" || path.startsWith("/index")) {
                    val status = JSONObject().apply {
                        put("status", "running")
                        put("port", repository.getServerPort())
                        put("allow_external", allowExternal)
                        put("sdk", Build.VERSION.SDK_INT)
                    }
                    sendHttpResponse(outputStream, 200, "application/json", status.toString())
                } else {
                    sendHttpError(outputStream, 404, "Not Found")
                }
            } else {
                sendHttpError(outputStream, 405, "Method Not Allowed")
            }

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            if (!wsConnections.contains(socket)) {
                socket.close()
            }
        }
    }

    private fun parseJsonToMap(jsonStr: String?): Map<String, Any?> {
        if (jsonStr.isNullOrBlank()) return emptyMap()
        val map = mutableMapOf<String, Any?>()
        try {
            val json = JSONObject(jsonStr)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.get(key)
                if (value == JSONObject.NULL) {
                    map[key] = null
                } else {
                    map[key] = value
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return map
    }

    private fun performWebSocketHandshake(out: OutputStream, key: String) {
        val acceptKey = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest(
                (key.trim() + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.UTF_8)
            ), Base64.NO_WRAP
        )

        val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $acceptKey\r\n\r\n"

        out.write(response.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private suspend fun handleWebSocketSession(socket: Socket, input: InputStream, out: OutputStream) {
        try {
            // Send connection established confirmation frame
            sendWsFrame(out, JSONObject().apply {
                put("type", "connection_established")
                put("message", "WebSocket IPC bridge active.")
            }.toString())

            while (socket.isConnected && !socket.isClosed) {
                val payload = readWebSocketFrame(input) ?: break
                if (payload.isNotBlank()) {
                    val reqJson = JSONObject(payload)
                    val id = reqJson.optString("id", "")
                    val category = reqJson.optString("category", "")
                    val action = reqJson.optString("action", "")
                    val paramsJson = reqJson.optJSONObject("params")
                    
                    val params = mutableMapOf<String, Any?>()
                    if (paramsJson != null) {
                        val keys = paramsJson.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            params[k] = paramsJson.get(k)
                        }
                    }

                    val response = ToolRegistry.execute(applicationContext, category, action, params)
                    response.put("id", id)

                    // Log Websocket call
                    repository.logApiCall(
                        method = "ws:$category.$action",
                        request = payload,
                        response = response.toString(),
                        status = if (response.optString("status") == "success") "SUCCESS" else "ERROR"
                    )

                    sendWsFrame(out, response.toString())
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            wsConnections.remove(socket)
            wsStreams.remove(socket)
            socket.close()
        }
    }

    private fun readWebSocketFrame(inputStream: InputStream): String? {
        val b1 = inputStream.read()
        if (b1 == -1) return null
        val fin = (b1 and 0x80) != 0
        val opcode = b1 and 0x0F
        if (opcode == 8) return null // connection close opcode
        
        val b2 = inputStream.read()
        if (b2 == -1) return null
        val isMasked = (b2 and 0x80) != 0
        var payloadLen = b2 and 0x7F
        
        if (payloadLen == 126) {
            val byte1 = inputStream.read()
            val byte2 = inputStream.read()
            payloadLen = (byte1 shl 8) or byte2
        } else if (payloadLen == 127) {
            for (i in 0 until 4) inputStream.read()
            var len = 0
            for (i in 0 until 4) {
                len = (len shl 8) or inputStream.read()
            }
            payloadLen = len
        }
        
        val mask = ByteArray(4)
        if (isMasked) {
            inputStream.read(mask)
        }
        
        val payload = ByteArray(payloadLen)
        var bytesRead = 0
        while (bytesRead < payloadLen) {
            val count = inputStream.read(payload, bytesRead, payloadLen - bytesRead)
            if (count == -1) break
            bytesRead += count
        }
        
        if (isMasked) {
            for (i in 0 until payloadLen) {
                payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
        }
        return String(payload, Charsets.UTF_8)
    }

    private fun sendWsFrame(out: OutputStream, text: String) {
        synchronized(out) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            out.write(0x81) // Fin = 1, Opcode = 1 (Text)
            val len = bytes.size
            if (len <= 125) {
                out.write(len)
            } else if (len <= 65535) {
                out.write(126)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            } else {
                out.write(127)
                out.write(0)
                out.write(0)
                out.write(0)
                out.write(0)
                out.write((len shr 24) and 0xFF)
                out.write((len shr 16) and 0xFF)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            out.write(bytes)
            out.flush()
        }
    }

    fun broadcastWsEvent(type: String, data: JSONObject) {
        val eventObj = JSONObject().apply {
            put("type", "event")
            put("event_type", type)
            put("data", data)
            put("timestamp", System.currentTimeMillis())
        }
        val eventText = eventObj.toString()
        scope.launch(Dispatchers.IO) {
            wsStreams.forEach { (_, stream) ->
                try {
                    sendWsFrame(stream, eventText)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun sendHttpResponse(out: OutputStream, code: Int, contentType: String, data: String) {
        val statusText = when (code) {
            200 -> "OK"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Internal Server Error"
        }
        val responseBytes = data.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $code $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${responseBytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"

        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write(responseBytes)
        out.flush()
    }

    private fun sendHttpError(out: OutputStream, code: Int, message: String) {
        val errObj = JSONObject().apply {
            put("status", "error")
            put("message", message)
        }
        sendHttpResponse(out, code, "application/json", errObj.toString())
    }

    private fun setupPhoneStateListener() {
        val tele = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        @Suppress("DEPRECATION")
        tele.listen(object : PhoneStateListener() {
            @Suppress("DEPRECATION")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                super.onCallStateChanged(state, phoneNumber)
                val stateText = when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> "RINGING"
                    TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
                    TelephonyManager.CALL_STATE_IDLE -> "IDLE"
                    else -> "UNKNOWN"
                }
                broadcastWsEvent("call_state_changed", JSONObject().apply {
                    put("state", stateText)
                    put("number", phoneNumber ?: "")
                })
            }
        }, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "gateway_channel",
                "System Bridge Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createStatusNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, "gateway_channel")
            .setContentTitle("System Bridge Active")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateForegroundNotification(content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(101, createStatusNotification(content))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
        serverSocket?.close()
        serverJob?.cancel()
        job.cancel()
        super.onDestroy()
    }
}
