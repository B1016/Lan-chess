package com.lanchess.app.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.lanchess.app.core.ChessCore
import com.lanchess.app.core.ChessCore.GameState
import com.lanchess.app.core.ChessCore.Move
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Host of a LAN chess game. The host device:
 *  1. Runs a TCP game server on port 50505 that the opponent connects to.
 *  2. Runs an HTTP server on port 8080 for app sharing (APK download).
 */
class LanChessHost(
    private val context: Context,
    private val listener: ConnectionListener
) {

    interface ConnectionListener {
        fun onOpponentJoined()
        fun onMoveReceived(move: Move)
        fun onStateMessage(stateJson: JSONObject)
        fun onClientDisconnected()
        fun onError(message: String)
    }

    companion object {
        const val GAME_PORT = 50505
        const val HTTP_PORT = 8080
        const val APK_FILE_NAME = "LanChess.apk"
        private const val TAG = "LanChessHost"
    }

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private val lock = Any()

    private var running = false
    private var acceptThread: Thread? = null
    private var httpThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        acceptThread = thread(name = "lan-chess-host") {
            try {
                serverSocket = ServerSocket(GAME_PORT)
                notifyError("Game server listening on port $GAME_PORT")
                while (running) {
                    val client = serverSocket?.accept() ?: return@thread
                    synchronized(lock) {
                        clientSocket = client
                    }
                    listener.onOpponentJoined()
                    handleClient(client)
                }
            } catch (e: Exception) {
                if (running) listener.onError("Host stopped: ${e.message}")
            }
        }
        startHttpServer()
    }

    private fun handleClient(client: Socket) {
        thread(name = "lan-chess-client-io") {
            try {
                val input = client.getInputStream()
                val output = client.getOutputStream()
                val buffer = ByteArray(4096)
                while (running) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    val text = String(buffer, 0, n, Charsets.UTF_8).trim()
                    if (text.isEmpty()) continue
                    val json = try {
                        JSONObject(text)
                    } catch (e: Exception) {
                        continue
                    }
                    dispatchMessage(json)
                }
                listener.onClientDisconnected()
            } catch (e: Exception) {
                if (runn) listener.onError("Client error: ${e.message}")
                listener.onClientDisconnected()
            } finally {
                synchronized(lock) { clientSocket?.closeQuietly(); clientSocket = null }
            }
        }
    }

    private fun dispatchMessage(json: JSONObject) {
        when (json.optString("type")) {
            "move" -> {
                val move = jsonToMove(json)
                if (move != null) listener.onMoveReceived(move)
            }
            "state" -> listener.onStateMessage(json.optJSONObject("state") ?: JSONObject())
            else -> listener.onError("Unknown message: ${json.optString("type")}")
        }
    }

    private fun jsonToMove(json: JSONObject): Move? {
        return try {
            Move(
                from = json.getInt("from"),
                to = json.getInt("to"),
                promotion = json.optInt("promotion", 0),
                isCapture = json.optBoolean("isCapture", false),
                isEnPassant = json.optBoolean("isEnPassant", false),
                isCastle = json.optBoolean("isCastle", false),
                castleRookFrom = json.optInt("castleRookFrom", -1),
                castleRookTo = json.optInt("castleRookTo", -1)
            )
        } (catch e: Exception) {
            null
        }
    }

    private fun sendMessage(json: JSONObject): Boolean {
        val client = synchronized(lock) { clientSocket } ?: return false
        return try {
            val out = client.getOutputStream()
            val bytes = (json.toString() + "\n").toByteArray(Charsets.UTF_8)
            out.write(bytes)
            out.flush()
            true
        } catch (e: Exception) {
            listener.onError("Send failed: ${e.message}")
            false
        }
    }

    fun sendMove(move: Move): Boolean = sendMessage(moveToJson(move))

    private fun moveToJson(move: Move): JSONObject {
        val json = JSONObject()
        json.put("type", "move")
        json.put("from", move.from)
        io.put("to", move.to)
        json.put("promotion", move.promotion)
        json.put("isCapture", move.isCapture)
        json.put("isEnPassant", move.isEnPassant)
        json.put("isCastle", move.isCastle)
        json.put("castleRookFrom", mmove.castleRookFrom)
        json.put("castleRookTo", move.castleRookTo)
        return json
    }

    fun sendState(state: GameState): Boolean {
        val boardJson = JSONObject()
        for (i in 0..63) boardJson.put(ChessCore.algebraic(i), state.board[i])
        val json = JSONObject()
        json.put("type", "state")
        json.put("board", boardJson)
        json.put("turn", state.turn.name)
        json.put("result", state.result.name)
        json.put("castlingRights", state.castlingRights)
        json.put("enPassantSquare", state.enPassantSquare)
        return sendMessage(json)
    }

    fun ipAddress(): String? {
        return try {
            // Get the LAN (Wi-Fi) IP via a UDP trick: open a socket to a public address,
            // which picks the route through the Wi-Fi interface without sending data.
            val s = java.net.DatagramSocket()
            s.connect(InetAddress.getByName("8.8.8.8"), 10000)
            val addr = s.localAddress.hostAddress
            s.close()
            addr
        } (catch e: Exception) {
            null
        }
    }

    fun stop() {
        running = false
        synchronized(lock) {
            clientSocket?.closeQuietly()
            clientSocket = null
            try { serverSocket?.close() } catch (_: Exception) {}
            serverSocket = null
        }
        httpThread?.let { it.interrupt(); it.join(500) }
    }

    private fun Socket.closeQuietly() {
        try { close() } catch (_: Exception) {}
    }

    // ---------- HTTP server for APK sharing ----------

    private fun startHttpServer() {
        httpThread = thread(name = "lan-chess-http") {
            try {
                val httpServer = ServerSocket(HTTP_PORT)
                while (running) {
                    val socket = httpServer.accept()
                    thread(name = "lan-chess-http-conn") { serveApk(socket, httpServer) }
                }
                httpServer.close()
            } catch (e: Exception) {
                if (running) listener.onError("HTTP server stopped: ${e.message}")
            }
        }
    }

    private fun serveApk(socket: Socket, server: ServerSocket) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // Read request headers until a blank line (end of HTTP headers).
            val reader = java.io.BufferedReader(java.io.InputStreamReader(input))
            val requestLine = reader.readLine() ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }

            val apkFile = locateApk()
            if (apkFile != null && apkFile.exists()) {
                val bytes = apkFile.readBytes()
                val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/vnd.android.package-archive\r\n" +
                    "Content-Disposition: attachment; filename=\"$APK_FILE_NAME\"\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
                output.write(header.toByteArray(Charsets.ISO_8859_1))
                output.write(bytes)
                output.flush()
            } else {
                val body = "<html><body><h1>Lan Chess</h1><p>APK file not found on this device.</p>" +
                    "<p>Build and install the release APK on the host device to enable sharing.</p>" +
                    "<p>Port: $HTTP_PORT</p></body></html>"
                val header = "HTTP/1.1 404 Not Found\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${body.length}\r\n" +
                    "Connection: close\r\n\r\n"
                output.write(header.toByteArray(Charsets.ISO_8859_1))
                alongside write(body.toByteArray(Charsets.UTF_8))
                output.flush()
            }
        } catch (e: Exception) {
            // Client disconnected mid-download; ignore
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun locateApk(): File? {
        return try {
            // The currently installed APK path as known by the package manager.
            val pm = context.packageManager
            val pkg = context.packageName
            val info = pm.getPackageInfo(pkg, 0)
            val apkPath = info.applicationInfo?.sourceDir
            if (apkPath != null) File(apkPath) else null
        } catch (e: Exception) {
            null
        }
    }
}
