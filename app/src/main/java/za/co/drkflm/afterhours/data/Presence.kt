package za.co.drkflm.afterhours.data

import androidx.compose.runtime.mutableIntStateOf
import io.socket.client.IO
import io.socket.client.Socket
import java.net.URI

/** Share the web/iOS authenticated presence socket, only while the app is in the foreground. */
object Presence {
    val revision = mutableIntStateOf(0)
    private var socket: Socket? = null

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
            connection.on(Socket.EVENT_CONNECT) { revision.intValue++ }
            connection.on("grid:changed") { revision.intValue++ }
            connection.connect()
            socket = connection
        } catch (_: Exception) {
            socket = null
        }
    }

    @Synchronized fun disconnect() {
        socket?.disconnect()
        socket?.off()
        socket = null
    }
}
