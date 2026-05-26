package org.ruject.gateway.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.ruject.gateway.data.repository.GatewayRepository
import org.ruject.gateway.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class GatewayBroadcastReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action == "org.ruject.gateway.ACTION_COMMAND") {
            val category = intent.getStringExtra("category") ?: return
            val action = intent.getStringExtra("action") ?: return
            val paramsJson = intent.getStringExtra("params") ?: "{}"
            val responseAction = intent.getStringExtra("response_action")

            scope.launch {
                val params = mutableMapOf<String, Any?>()
                try {
                    val json = JSONObject(paramsJson)
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        params[k] = json.get(k)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                val result = ToolRegistry.execute(context, category, action, params)

                // Log action Call in db
                val repository = GatewayRepository(context)
                repository.logApiCall(
                    method = "broadcast:$category.$action",
                    request = paramsJson,
                    response = result.toString(),
                    status = if (result.optString("status") == "success") "SUCCESS" else "ERROR"
                )

                // Broadcast back the response if requesting app wants a return
                if (!responseAction.isNullOrBlank()) {
                    val replyIntent = Intent(responseAction).apply {
                        putExtra("status", result.optString("status"))
                        putExtra("response", result.toString())
                        // Make intent safe and accessible
                        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    }
                    context.sendBroadcast(replyIntent)
                }
            }
        }
    }
}
