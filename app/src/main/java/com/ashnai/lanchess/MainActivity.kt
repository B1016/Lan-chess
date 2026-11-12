package com.ashnai.lanchess

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-activity app. Shows the LAN Chess menu, then swaps in the game
 * screen (board + status bar) for host or guest play.
 */
class MainActivity : AppCompatActivity() {

    private var state = ChessEngine.initialPosition()
    private var playerColor = ChessEngine.WHITE
    private var role = Role.NONE
    private var server: ChessServer? = null
    private var client: ChessClient? = null
    private var boardView: BoardView? = null
    private var statusText: TextView? = null
    private var gameStarted = AtomicBoolean(false)
    private val appliedMoves = java.util.HashSet<String>()

    private enum class Role { NONE, HOST, GUEST }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showMenu()
    }

    private fun showMenu() {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(64), dp(32), dp(32))
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        val title = TextView(this).apply {
            text = "LAN Chess"
            textSize = 32f
            setTextColor(0xFF333333.toInt())
            gravity = android.view.Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Play chess with anyone on your local network"
            textSize = 14f
            setTextColor(0xFF666666.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(8), 0, dp(40))
        }
        fun makeButton(label: String, onClick: () -> Unit): Button =
            Button(this).apply {
                text = label
                setOnClickListener { onClick() }
            }

        root.addView(title)
        root.addView(subtitle)
        root.addView(makeButton("Host Game") { startHost() })
        root.addView(makeButton("Join Game") { promptJoinIp() })
        root.addView(makeButton("Share App") { startActivity(android.content.Intent(this, ShareActivity::class.java)) })
        setContentView(root)
    }

    private fun startHost() {
        role = Role.HOST
        playerColor = ChessEngine.WHITE
        server = ChessServer(onMove = { handleRemoteMove(it) })
        try {
            val ip = server?.start(port = 5000) ?: run {
                Toast.makeText(this, "Could not start server", Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(this, "Hosting. Your IP: $ip", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Server error: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }
        showGame()
    }

    private fun promptJoinIp() {
        val input = EditText(this).apply {
            hint = "Host IP, e.g. 192.168.1.50"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val wrap = FrameLayout(this).apply { setPadding(dp(32), 0, dp(32), 0); addView(input) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Join Game")
            .setMessage("Enter the host's IP address shown on their screen.")
            .setView(wrap)
            .setPositiveButton("Connect") { _, _ -> startGuest(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startGuest(ip: String) {
        if (ip.isBlank()) return
        role = Role.GUEST
        playerColor = ChessEngine.BLACK
        client = ChessClient(
            host = ip,
            port = 5000,
            onMove = { handleRemoteMove(it) },
            onConnected = {
                runOnUiThread { Toast.makeText(this, "Connected!", Toast.LENGTH_SHORT).show() }
            },
            onDisconnected = {
                runOnUiThread { statusText?.text = "Opponent disconnected" }
            }
        )
        client?.connect()
        showGame()
    }

    // ------------------------------------------------------------------
    // Game screen
    // ------------------------------------------------------------------

    @SuppressLint("SetTextI18n")
    private fun showGame() {
        state = ChessEngine.initialPosition()
        gameStarted.set(true)
        appliedMoves.clear()

        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(24), dp(8), dp(8))
        }
        statusText = TextView(this).apply {
            textSize = 16f
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        boardView = BoardView(this, state, playerColor) { from, to -> handleTap(from, to) }

        root.addView(statusText)
        root.addView(boardView)
        root.addView(Button(this).apply {
            text = "Restart Game"
            setOnClickListener { restartGame() }
        })
        root.addView(Button(this).apply {
            text = "Back to Menu"
            setOnClickListener { backToMenu() }
        })
        setContentView(root)
        refreshStatus()
    }

    private fun handleTap(from: Int?, to: Int) {
        if (state.turn != playerColor) {
            Toast.makeText(this, "Not your turn", Toast.LENGTH_SHORT).show()
            return
        }
        val legal = ChessEngine.legalMoves(state, playerColor)
        if (from != null && legal.any { it.first == from && it.second == to }) {
            val promo = if (needsPromotion(from, to)) ChessEngine.QUEEN else {
                legal.first { it.first == from && it.second == to }.third
            }
            applyLocalMove(from, to, promo)
        } else {
            // Select a new piece (or re-select).
            val moves = legal.filter { it.first == to }
            if (moves.isNotEmpty()) {
                boardView?.showSelection(to, moves.map { it.second }.distinct())
            } else {
                boardView?.clearSelection()
            }
        }
    }

    private fun needsPromotion(from: Int, to: Int): Boolean {
        val piece = state.board[from]
        return ChessEngine.textToMove("") == null && BoardUtils.pieceType(piece) == ChessEngine.PAWN &&
            (to / 8 == 0 || to / 8 == 7)
    }

    private fun applyLocalMove(from: Int, to: Int, promo: Int) {
        val text = ChessEngine.moveToText(from, to, promo)
        if (!appliedMoves.add(text)) return
        val next = ChessEngine.applyMove(state, from, to, promo)
        setState(next, from, to)
        when (role) {
            Role.HOST -> server?.send(text)
            Role.GUEST -> client?.send(text)
            else -> {}
        }
        refreshStatus()
    }

    private fun handleRemoteMove(text: String) {
        if (!gameStarted.get()) return
        val parsed = ChessEngine.textToMove(text) ?: return
        val (from, to, promo) = parsed
        // Verify legality to stay in sync and resist nonsense input.
        if (!ChessEngine.legalMoves(state, 1 - playerColor).any { it.first == from && it.second == to && it.third == promo }) {
            if (promo == ChessEngine.EMPTY && ChessEngine.legalMoves(state, 1 - playerColor)
                    .any { it.first == from && it.second == to }
            ) {
                setState(ChessEngine.applyMove(state, from, to, promo), from, to)
                refreshStatus()
                return
            }
            runOnUiThread { statusText?.text = "Invalid move received" }
            return
        }
        setState(ChessEngine.applyMove(state, from, to, promo), from, to)
        refreshStatus()
    }
}
