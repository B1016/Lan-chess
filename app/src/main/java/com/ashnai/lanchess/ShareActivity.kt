package com.ashnai.lanchess

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Share screen: starts the APK-sharing web server and shows the URL
 * for others on the LAN to download the app.
 */
class ShareActivity : AppCompatActivity() {

    private var shareServer: ApkShareServer? = null
    private var urlText: TextView? = null
    private var statusText: TextView? = null
    private var toggleButton: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shareServer = ApkShareServer(this)
        buildUi()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("SetTextI18n")
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(64), dp(32), dp(32))
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        root.addView(TextView(this).apply {
            text = "Share LAN Chess"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Start the server, then open the URL below on the other device's browser to download and install the app."
            textSize = 14f
            setPadding(0, dp(12), 0, dp(24))
        })
        statusText = TextView(this).apply {
            textSize = 16f
            gravity = android.view.Gravity.CENTER
        }
        urlText = TextView(this).apply {
            textSize = 18f
            setTextColor(0xFF1A5FB4.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(8), 0, dp(24))
        }
        toggleButton = Button(this).apply {
            text = "Start Server"
            setOnClickListener { toggle() }
        }
        val copyButton = Button(this).apply {
            text = "Copy URL"
            setOnClickListener { copyUrl() }
        }
        root.addView(statusText)
        root.addView(urlText)
        root.addView(toggleButton)
        root.addView(copyButton)
        setContentView(root)
        refreshUi()
    }

    private fun toggle() {
        val s = shareServer ?: return
        if (s.isRunning()) {
            s.stop()
        } else {
            try {
                val url = s.start(port = 8080)
                if (url == null) {
                    Toast.makeText(this, "No LAN IP found", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Could not start: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        refreshUi()
   

    private fun refreshUi() {
        val s = shareServer
        if (s == null || !s.isRunning()) {
            statusText?.text = "Server is off"
            urlText?.text = ""
            toggleButton?.text = "Start Server"
        } else {
            statusText?.text = "Server is running"
            urlText?.text = s.currentUrl()
            toggleButton?.text = "Stop Server"
        }
    }

    private fun copyUrl() {
        val url = shareServer?.currentUrl() ?: run {
            Toast.makeText(this, "Server not running", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("LAN Chess URL", url))
        Toast.makeText(this, "URL copied", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        shareServer?.stop()
    }
}
