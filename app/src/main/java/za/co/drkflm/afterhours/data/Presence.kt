package za.co.drkflm.afterhours.data

import androidx.compose.runtime.mutableIntStateOf
import io.socket.client.IO
import io.socket.client.Socket
import java.net.URI
import android.util.Log
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/** Share the web/iOS authenticated presence socket, only while the app is in the foreground. */
object Presence {
    val revision = mutableIntStateOf(0)
    val typingConversation = androidx.compose.runtime.mutableStateOf<String?>(null)
    val typingRevision = mutableIntStateOf(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    fun sendTyping(conversationId:String, typing:Boolean) {
        socket?.emit("typing", JSONObject().put("conversationId",conversationId).put("typing",typing))
    }
    private var socket: Socket? = null
    private const val TAG = "AFTRHRS-Presence"

    @Synchronized fun connect() {
        if (socket != null) return
        val cookie = Network.cookieHeader()
        if (cookie.isBlank()) return
        try {
            val options = IO.Options().apply {
                path = "/socket.io"
                transports = arrayOf("websocket", "polling")
                reconnection = true
                extraHeaders = mapOf("Cookie" to listOf(cookie))
            }
            val connection = IO.socket(URI.create(ApiClient.ORIGIN), options)
            connection.on(Socket.EVENT_CONNECT) { Log.i(TAG, "Presence connected"); revision.intValue++ }
            connection.on(Socket.EVENT_CONNECT_ERROR) { args -> Log.w(TAG, "Presence connection failed: " + args.joinToString()) }
            connection.on(Socket.EVENT_DISCONNECT) { Log.i(TAG, "Presence disconnected"); revision.intValue++ }
            connection.on("grid:changed") { revision.intValue++ }
            connection.on("typing") { args ->
                val event=args.firstOrNull() as? JSONObject
                val conversationId=event?.optString("conversationId").orEmpty()
                if(conversationId.isNotBlank()) mainHandler.post {
                    typingConversation.value=if(event?.optBoolean("typing")==true)conversationId else null
                    typingRevision.intValue++
                }
            }
            connection.connect()
            socket = connection
        } catch (error: Exception) {
            Log.e(TAG, "Could not start presence connection", error)
            socket = null
        }
    }

    @Synchronized fun reconnect() {
        disconnect()
        connect()
    }

    @Synchronized fun disconnect() {
        socket?.disconnect()
        socket?.off()
        socket = null
        typingConversation.value = null
    }
}
