package com.lanchess.app.net

import android.content.Context
import com.lanchess.app.core.ChessCore
import com.lanchess.app.core.ChessCore.GameState
import com.lanchess.app.core.ChessCore.Move
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.DatagramSocket
import java.net.InetAddress
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
    }

    private var gameServerSocket: ServerSocket? = null
    private var httpServerSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private val lock = Any()

    @Volatile private var running = false
    private var acceptThread: Thread? = null
    private var httpThread: Thread? = null

    fun start() {
        if (running) return
        running = true

        // Game server (TCP)
        acceptThread = thread(name = "lan-chess-host") {
            try {
                val server = ServerSocket(GAME_PORT)
                synchronized(lock) { gameServerSocket = server }
                while (running) {
                    val client = server.accept() ?: return@thread
                    synchronized(lock) { clientSocket = client }
                    listener.onOpponentJoined()
                    handleClient(client)
                }
            } catch (e: Exception) {
                if (running) listener.onError("Game server stopped: ${e.message}")
            }
        }

        // Sharing server (HTTP)
        startHttpServer()
    }

    private fun handleClient(client: Socket) {
        thread(name = "lan-chess-client-io") {
            try {
                val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                while (running) {
                    val text = input.readLine() ?: break
                    if (text.isBlank()) continue
                    val json = try {
                        JSONObject(text)
                    } catch (e: Exception) {
                        continue
                    }
                    dispatchMessage(json)
                }
                listener.onClientDisconnected()
            } catch (e: Exception) {
                if (running) listener.onError("Client error: ${e.message}")
                listener.onClientDisconnected()
            } finally {
                synchronized(lock) {
                    clientSocket?.closeQuietly()
                    clientSocket = null
                }
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
            else -> listener.onError("Unknown message type: ${json.optString("type")}")
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
        } catch (e: Exception) {
            null
        }
    }

    fun sendMove(move: Move): Boolean = sendMessage(moveToJson(move))

    private fun moveToJson(move: Move): JSONObject {
        val json = JSONObject()
        json.put("type", "move")
        json.put("from", move.from)
        json.put("to", move.to)
        java_jsonPutMoveExtras(json, move)
        return json
    }

    private fun java_jsonPutMoveExtras(json: JSONObject, move: Move) {
        json.put("promotion", move.promotion)
        json.put("isCapture", move.isCapture)
        json.put("isEnPassant", move.isEnPassant)
        json.put("isCastle", move.isCastle)
        json.put("castleRookFrom", move.castleRookFrom)
        json.put("castleRookTo", move.castleRookTo)
    }

    private fun sendMessage(json: JSONObject): Boolean {
        val client = synchronized(lock) { clientSocket } ?: return false
        return try {
            val out = client.getOutputStream()
            val bytes = (json.toString() + "\n").toByteArray(Charsets.UTF_8)
            synchronized(client) {
                out.write(bytes)
                out.flush()
            }
            true
        } catch (e: Exception) {
            listener.onError("Send failed: ${e.message}")
            false
    }
    }

    fun sendState(state: GameState): Boolean {
        val boardJson = JSONObject()
        for (i in 0..63) boardJson.put(ChessCore.algebraic(i), state.board[i])
        val json = JSONObject()
        json.put("type", "state")
        json.put("board", boardJson)
        quickPutState(json, state)
        return sendMessage(json)
    }

    private fun quickPutState(json: JSONObject, state: GameState) {
        json.put("turn", state.turn.name)
        json.put("result", state.result.name)
        json.put("castlingRights", state.castlingRights)
        json.put("enPassantSquare", state.enPassantSquare)
    }

    /** The device's Wi-Fi LAN IP, or null if unavailable. */
    fun ipAddress(): String? {
        return try {
            // UDP trick: connect() to a public address without sending data,
            // which binds the socket to the Wi-Fi interface's local address.
            val s = DatagramSocket()
            s.connect(InetAddress.getByName("8.8.8.8"), 10000)
            val addr = s.localAddress.hostAddress
            s.close()
            addr
        } catch (e: Exception) {
            null
        }
    }

    fun stop() {
        running = false
        synchronized(lock) {
            clientSocket?.closeQuietly()
            clientSocket = null
            try { gameServerSocket?.close() } catch (_: Exception) {}
            gameServerSocket = null
            try { httpServerSocket?.close() } catch (_: Exception) {}
            httpServerSocket = null
        }
        try { acceptThread?.join(500) } catch (_: InterruptedException) {}
        try { httpThread?.join(500) } catch (_: InterruptedException) {}
    }

    private fun Socket.closeQuietly() {
        try { close() } catch (_: Exception) {}
    }

    // ---------- HTTP server for APK sharing ----------

    private fun startHttpServer() {
        httpThread = thread(name = "lan-chess-http") {
            try {
                val server = ServerSocket(HTTP_PORT)
                synchronized(lock) { httpServerSocket = server }
                while (running) {
                    val socket = server.accept()
                    thread(name = "lan-chess-http-conn") { serveApk(socket) }
                }
                server.close()
            } catch (e: Exception) {
                if (running) listener.onError("HTTP server stopped: ${e.message}")
            }
        }
    }

    private fun serveApk(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // Read request headers until the blank line.
            val reader = BufferedReader(InputStreamReader(input, Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
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
                val body = ("<html><body><h1>Lan Chess</h1>" +
                    "<p>APK file not found on this device.</p>" +
                    "<p>Install Lan Chess on this host device first to enable sharing.</p></body></html>")
                    .toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 404 Not Found\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n"
                output.write(header.toByteArray(Charsets.ISO_8859_1))
                output.write(body)
                output.flush()
            }
        } catch (e: Exception) {
            // Client disconnected mid-download; ignore
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * Locate the installed APK for this app. Once Lan Chess is installed on
     * the host device, this path can be served to other devices for sharing.
     */
    private fun locateApk(): File? {
        return try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(context.packageName, 0)
            val apkPath = info.applicationInfo?.sourceDir
            if (apkPath != null) File(apkPath) else null
        } catch (e: Exception) {
            null
        }
    }
}
