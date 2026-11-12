package com.ashnai.lanchess

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * Custom-drawn 8x8 chessboard with tap-to-move interaction:
 * tap a piece, then tap the destination square. The view is purely
 * presentational; legality is decided by MainActivity via the engine.
 */
class BoardView(
    context: Context,
    private var state: ChessEngine.GameState,
    private var playerColor: Int,
    private val onTap: (from: Int?, to: Int) -> Unit
) : View(context) {

    private val lightPaint = Paint().apply { color = Color.parseColor("#F0D9B5") }
    private val darkPaint = Paint().apply { color = Color.parseColor("#B58863") }
    private val selPaint = Paint().apply { color = Color.parseColor("#70C0C060") }
    private val hintPaint = Paint().apply { color = Color.parseColor("#60A00000") }
    private val piecePaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    private val lastMovePaint = Paint().apply { color = Color.parseColor("#50FFD700") }
    private val checkPaint = Paint().apply { color = Color.parseColor("#70FF0000") }

    private var selected: Int? = null
    private var legalTargets: List<Int> = emptyList()
    private var lastMove: Pair<Int, Int>? = null
    private var checkSquare: Int? = null
    private var cellSize = 0f

    fun updateState(newState: ChessEngine.GameState, lastFrom: Int?, lastTo: Int?, checkSq: Int? = null) {
        state = newState
        selected = null
        legalTargets = emptyList()
        if (lastFrom != null && lastTo != null) lastMove = Pair(lastFrom, lastTo)
        checkSquare = checkSq
        invalidate()
    }

    fun clearSelection() {
        selected = null
        legalTargets = emptyList()
        invalidate()
    }

    fun showSelection(from: Int, targets: List<Int>) {
        selected = from
        legalTargets = targets
        invalidate()
    }

    fun setPlayerColor(color: Int) {
        playerColor = color
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cellSize = min(w, h) / 8f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Board squares.
        for (i in 0..63) {
            val (x, y) = squareToXY(i)
            canvas.drawRect(x, y, x + cellSize, y + cellSize, if (BoardUtils.isDarkSquare(i)) darkPaint else lightPaint)
        }

        // Last-move highlight.
        lastMove?.let { (f, t) ->
            listOf(f, t).forEach { i ->
                val (x, y) = squareToXY(i)
                canvas.drawRect(x, y, x + cellSize, y + cellSize, lastMovePaint)
            }
        }

        // Check highlight.
        checkSquare?.let { i ->
            val (x, y) = squareToXY(i)
            canvas.drawRect(x, y, x + cellSize, y + cellSize, checkPaint)
        }

        // Selected square and legal-target hints.
        selected?.let { s ->
            val (x, y) = squareToXY(s)
            canvas.drawRect(x, y, x + cellSize, y + cellSize, selPaint)
        }
        for (t in legalTargets) {
            val (x, y) = squareToXY(t)
            canvas.drawCircle(x + cellSize / 2, y + cellSize / 2, cellSize / 5f, hintPaint)
        }

        // Pieces.
        piecePaint.textSize = cellSize * 0.75f
        for (i in 0..63) {
            val p = state.board[i]
            if (p == ChessEngine.EMPTY) continue
            val (x, y) = squareToXY(i)
            val glyph = BoardUtils.pieceGlyph(p)
            piecePaint.color = if (BoardUtils.pieceColor(p) == ChessEngine.WHITE) Color.WHITE else Color.BLACK
            piecePaint.setShadowLayer(3f, 1f, 1f, Color.GRAY)
            val baseline = y + cellSize / 2 + piecePaint.textSize / 3
            canvas.drawText(glyph, x + cellSize / 2, baseline, piecePaint)
            piecePaint.clearShadowLayer()
        }
    }

    /** Flips the board for the black player so their pieces start at the bottom. */
    private fun squareToXY(i: Int): Pair<Float, Float> {
        val flipped = playerColor == ChessEngine.BLACK
        val rank = i / 8
        val file = i % 8
        val drawRank = if (flipped) 7 - rank else rank
        val drawFile = if (flipped) 7 - file else file
        return Pair(drawFile * cellSize, drawRank * cellSize)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        val flipped = playerColor == ChessEngine.BLACK
        val col = (event.x / cellSize).toInt()
        val row = (event.y / cellSize).toInt()
        if (col !in 0..7 || row !in 0..7) return true
        val file = if (flipped) 7 - col else col
        val rank = if (flipped) 7 - row else row
        onTap(selected, rank * 8 + file)
        return true
    }
}
