package com.ashnai.lanchess

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.HashSet
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private var state = ChessEngine.initialPosition()
    private var playerColor = ChessEngine.WHITE
    private var role = Role.NONE
    private var server: ChessServer? = null
    private var client: ChessClient? = null
    private var boardView: BoardView? = null
    private var statusText: TextView? = null
    private var gameStarted = AtomicBoolean(false)
    private val appliedMoves = HashSet<String>()

    private enum class Role { NONE, HOST, GUEST }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showMenu()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------
    // Menu
    // ------------------------------------------------------------------

    private fun showMenu() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(64), dp(32), dp(32))
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        root.addView(TextView(this).apply {
            text = "LAN Chess"
            textSize = 32f
            setTextColor(0xFF333333.toInt())
            gravity = android.view.Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Play chess with anyone on your local network"
            textSize = 14f
            setTextColor(0xFF666666.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(8), 0, dp(40))
        })
        root.addView(button("Host Game") { startHost() })
        root.addView(button("Join Game") { promptJoinIp() })
        root.addView(button("Share App") {
            startActivity(android.content.Intent(this@MainActivity, ShareActivity::class.java))
        })
        setContentView(root)
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    // ------------------------------------------------------------------
    // Host / Guest
    // ------------------------------------------------------------------

    private fun startHost() {
        role = Role.HOST
        playerColor = ChessEngine.WHITE
        val s = ChessServer(onMove = { handleRemoteMove(it) })
        try {
            val ip = s.start(port = 5000) ?: run {
                Toast.makeText(this, "Not on Wi-Fi or hotspot — no LAN IP", Toast.LENGTH_LONG).show()
                return
            }
            server = s
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
        AlertDialog.Builder(this)
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
        client = ChessClient(
            host = ip,
            port = 5000,
            onMove = { handleRemoteMove(it) },
            onConnected = { runOnUiThread { Toast.makeText(this, "Connected!", Toast.LENGTH_SHORT).show() } },
            onDisconnected = { runOnUiThread { statusText?.text = "Opponent disconnected" } }
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
        root.addView(button("Restart Game") { confirmRestart() })
        root.addView(button("Back to Menu") { backToMenu() })
        setContentView(root)
        refreshStatus()
    }

    // ------------------------------------------------------------------
    // Move handling
    // ------------------------------------------------------------------

    private fun handleTap(from: Int?, to: Int) {
        if (state.turn != playerColor) {
            Toast.makeText(this, "Not your turn", Toast.LENGTH_SHORT).show()
            return
        }
        val legal = ChessEngine.legalMoves(state, playerColor)
        val move = legal.firstOrNull { it.first == from && it.second == to }
        if (from != null && move != null) {
            if (isPromotion(from, to)) {
                pickPromotion(from, to)
            } else {
                applyLocalMove(from, to, move.third)
            }
        } else {
            val moves = legal.filter { it.first == to }
            if (moves.isNotEmpty()) {
                boardView?.showSelection(to, moves.map { it.second }.distinct())
            } else {
                boardView?.clearSelection()
            }
        }
    }

    private fun isPromotion(from: Int, to: Int): Boolean {
        val piece = state.board[from]
        return BoardUtils.pieceType(piece) == ChessEngine.PAWN && (to / 8 == 0 || to / 8 == 7)
    }

    private fun pickPromotion(from: Int, to: Int) {
        val options = arrayOf("Queen", "Rook", "Bishop", "Knight")
        val promos = intArrayOf(ChessEngine.QUEEN, ChessEngine.ROOK, ChessEngine.BISHOP, ChessEngine.KNIGHT)
        AlertDialog.Builder(this)
            .setTitle("Promote pawn to:")
            .setItems(options) { _, which -> applyLocalMove(from, to, promos[which]) }
            .setOnCancelListener { boardView?.clearSelection() }
            .show()
    }

    private fun applyLocalMove(from: Int, to: Int, promo: Int) {
        val text = ChessEngine.moveToText(from, to, promo)
        if (!appliedMoves.add(text)) return
        setState(ChessEngine.applyMove(state, from, to, promo), from, to)
        when (role) {
            Role.HOST -> server?.send(text)
            Role.GUEST -> client?.send(text)
            Role.NONE -> {}
        }
        refreshStatus()
    }

    private fun handleRemoteMove(text: String) {
        runOnUiThread {
            if (!gameStarted.get()) return@runOnUiThread
            if (text.equals("reset", ignoreCase = true)) {
                state = ChessEngine.initialPosition()
                appliedMoves.clear()
                boardView?.updateState(state, null, null, null)
                refreshStatus()
                return@runOnUiThread
            }
            val parsed = ChessEngine.textToMove(text) ?: return@runOnUiThread
            val (from, to, promo) = parsed
            val legal = ChessEngine.legalMoves(state, 1 - playerColor)
            val exact = legal.firstOrNull { it.first == from && it.second == to && it.third == promo }
            val loose = if (exact == null && promo == ChessEngine.EMPTY) {
                legal.firstOrNull { it.first == from && it.second == to }
            } else null
            when {
                exact != null -> setState(ChessEngine.applyMove(state, from, to, promo), from, to)
                loose != null -> setState(ChessEngine.applyMove(state, from, to, loose.third), from, to)
                else -> {
                    statusText?.text = "Invalid move received"
                    return@runOnUiThread
                }
            }
            refreshStatus()
        }
    }

    // ------------------------------------------------------------------
    // State / UI refresh
    // ------------------------------------------------------------------

    private fun setState(next: ChessEngine.GameState, from: Int, to: Int) {
        state = next
        val checkSq = if (ChessEngine.isInCheck(state, state.turn)) {
            val kingPiece = ChessEngine.KING or (if (state.turn == ChessEngine.WHITE) 0 else ChessEngine.BLACK_FLAG)
            (0..63).firstOrNull { state.board[it] == kingPiece }
        } else null
        boardView?.updateState(state, from, to, checkSq)
    }

    private fun refreshStatus() {
        val status = ChessEngine.status(state)
        val turnText = when (state.turn) {
            ChessEngine.WHITE -> "White to move"
            else -> "Black to move"
        }
        statusText?.text = when (status) {
            ChessEngine.Status.CHECK -> "Check! $turnText"
            ChessEngine.Status.CHECKMATE ->
                if (state.turn == ChessEngine.WHITE) "Checkmate — Black wins" else "Checkmate — White wins"
            ChessEngine.Status.STALEMATE -> "Stalemate — draw"
            ChessEngine.Status.DRAW_HALFMOVE -> "Draw by 50-move rule"
            ChessEngine.Status.DRAW_INSUFFICIENT -> "Draw — insufficient material"
            ChessEngine.Status.ACTIVE -> turnText
        }
    }

    private fun confirmRestart() {
        AlertDialog.Builder(this)
            .setTitle("Restart")
            .setMessage("Restart the game on both devices?")
            .setPositiveButton("Restart") { _, _ ->
                sendControl("reset")
                state = ChessEngine.initialPosition()
                appliedMoves.clear()
                boardView?.updateState(state, null, null, null)
                refreshStatus()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendControl(message: String) {
        when (role) {
            Role.HOST -> server?.send(message)
            Role.GUEST -> client?.send(message)
            Role.NONE -> {}
        }
    }

    private fun backToMenu() {
        server?.stop()
        client?.disconnect()
        server = null
        client = null
        role = Role.NONE
        gameStarted.set(false)
        showMenu()
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        client?.disconnect()
    }
}
