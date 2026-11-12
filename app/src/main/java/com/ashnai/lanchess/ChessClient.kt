package com.ashnai.lanchess

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP chess client. The guest player (Black) connects to the host's IP.
 * Same newline-delimited protocol as the server: plain coordinate moves.
 */
class ChessClient(
    private val host: String,
    private val port: Int = 5000,
    private val onMove: (String) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit
) {

    @Volatile private var socket: Socket? = null
    @Volatile private var connected = false

    fun connect() {
        Thread {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 4000)
                socket = s
                connected = true
                onConnected()
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                while (connected && s.isConnected) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    onMove(line.trim())
                }
            } catch (_: Exception) {
            } finally {
                connected = false
                onDisconnected()
            }
        }.start()
    }

    fun send(message: String) {
        val s = socket ?: return
        try {
            val writer = PrintWriter(s.getOutputStream(), true)
            writer.println(message)
        } catch (_: Exception) {}
    }

    fun disconnect() {
        connected = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }
}
