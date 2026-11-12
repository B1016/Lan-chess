package com.ashnai.lanchess

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Tiny HTTP server that shares the app's own APK over the LAN.
 * Browsing to http://<host-ip>:8080 shows a minimal download page and
 * serves the APK at /app.apk.
 */
class ApkShareServer(private val context: Context) {

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    fun start(port: Int = 8080): String? {
        if (running) return currentUrl()
        serverSocket = ServerSocket(port, 5, java.net.InetAddress.getByName("0.0.0.0"))
        running = true
        Thread { acceptLoop() }.start()
        return currentUrl()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    fun isRunning(): Boolean = running

    fun currentUrl(): String? {
        val ip = ChessServer.getLanIp() ?: return null
        val port = serverSocket?.localPort ?: 8080
        return "http://$ip:$port"
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (running) {
            val socket = try {
                ss.accept()
            } catch (e: Exception) {
                break
            }
            Thread { handle(socket) }.start()
        }
    }

    private fun handle(socket: Socket) {
        try {
            val reader = java.io.BufferedReader(java.io.InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val path = requestLine.split(" ").getOrNull(1) ?: "/"

            val output = socket.getOutputStream()
            when {
                path == "/app.apk" -> {
                    val apk = resolveApk() ?: run {
                        writeText(output, "500 Internal Server Error", "APK not found.")
                        return
                    }
                    writeText(output, "200 OK", "application/vnd.android.package-archive", null, apk.length())
                    FileInputStream(apk).use { fin ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = fin.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                        }
                    }
                }
                path == "/" || path == "/index.html" -> writeHtml(output)
                else -> writeText(output, "404 Not Found", "Not found.")
            }
            output.flush()
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun resolveApk(): File? = try {
        context.packageManager.getApplicationInfo(context.packageName, 0).let { File(it.sourceDir) }
    } catch (_: Exception) {
        null
    }

    private fun writeText(out: java.io.OutputStream, status: String, body: String) =
        writeText(out, status, "text/plain; charset=utf-8", body.toByteArray(StandardCharsets.UTF_8))

    private fun writeText(
        out: java.io.OutputStream,
        status: String,
        contentType: String,
        body: ByteArray? = null,
        contentLength: Long = 0L
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.0 ").append(status).append("\r\n")
        sides = null
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(contentLength).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
        body?.let { out.write(it) }
    }

    private fun writeHtml(out: java.io.OutputStream) {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>LAN Chess - Download</title>
                <style>
                    body { font-family: sans-serif; background: #2b2b2b; color: #eee; display: flex;
                           flex-direction: column; align-items: center; justify-content: center;
                           height: 100%; margin: 0; }
                    h1 { font-size: 2em; }
                    a.button { display: inline-block; margin-top: 1em; padding: 14px 32px;
                               background: #B58863; color: #fff; text-decoration: none;
                               border-radius: 8px; font-size: 1.2em; }
                    p { color: #aaa; }
                </style>
            </head>
            <body>
                <h1>LAN Chess</h1>
                <p>Tap below to download and install. Enable "Install unknown apps" if asked.</p>
                <a class="button" href="/app.apk">Download APK</a>
            </body>
            </html>
        """.trimIndent()
        writeText(out, "200 OK", "text/html; charset=utf-8", html.toByteArray(StandardCharsets.UTF_8), html.length.toLong())
    }
}
