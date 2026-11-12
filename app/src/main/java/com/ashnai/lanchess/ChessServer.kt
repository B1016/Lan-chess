package com.ashnai.lanchess

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/**
 * TCP chess server. The host player (White) runs this on port 5000.
 * Protocol: newline-delimited text. Each move is sent as coordinate text,
 * e.g. "e2e4". Both sides apply the same move to their own engine copy,
 * so no full-board sync is needed.
 */
class ChessServer(private val onMove: (String) -> Unit) {

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var clientSocket: Socket? = null
    @Volatile private var running = false

    /** Starts listening; returns the LAN IP the guest should connect to, or null. */
    fun start(port: Int = 5000): String? {
        if (running) return getLanIp()
        serverSocket = ServerSocket(port, 1, InetAddress.getByName("0.0.0.0"))
        running = true
        Thread { acceptLoop() }.start()
        return getLanIp()
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (running) {
            val client = try {
                ss.accept()
            } catch (e: Exception) {
                break
            }
            clientSocket?.close()
            clientSocket = client
            Thread { readLoop(client) }.start()
        }
    }

    private fun readLoop(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            while (running && socket.isConnected) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                onMove(line.trim())
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /** Sends a move (or control message) to the connected guest. */
    fun send(message: String) {
        val socket = clientSocket ?: return
        Thread {
            try {
                val writer = PrintWriter(socket.getOutputStream(), true)
                writer.println(message)
            } catch (_: Exception) {}
        }.start()
    }

    fun stop() {
        running = false
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        clientSocket = null
        serverSocket = null
    }

    fun isRunning(): Boolean = running

    companion object {
        /** Best-effort LAN IPv4 of this device (site-local only). */
        fun getLanIp(): String? {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
                var result: String? = null
                for (nif in Collections.list(interfaces)) {
                    if (nif.isUp && !nif.isLoopback && !nif.isVirtual) {
                        for (addr in Collections.list(nif.inetAddresses)) {
                            if (!addr.isLoopbackAddress && addr is java.net.Inet4Address &&
                                addr.isSiteLocalAddress) {
                                result = addr.hostAddress
                            }
                        }
                    }
                }
                result
            } catch (_: Exception) {
                null
    }
        }
    }
}
