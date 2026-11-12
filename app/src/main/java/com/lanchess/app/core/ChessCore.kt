package com.lanchess.app.core

import kotlin.math.abs

/**
 * Lan Chess core rules engine.
 *
 * Squares are indexed 0..63 where a1 = 0, h1 = 7, a8 = 56, h8 = 63.
 * Piece values are positive for white and negative for black.
 */
object ChessCore {

    enum class Color { WHITE, BLACK }

    enum class GameResult { ONGOING, WHITE_WIN, BLACK_WIN, DRAW }

    object Pieces {
        const val PAWN = 1
        const val KNIGHT = 2
        const val BISHOP = 3
        const val ROOK = 4
        const val QUEEN = 5
        const val KING = 6
    }

    data class Move(
        val from: Int,
        val to: Int,
        val promotion: Int = 0,
        val isCapture: Boolean = false,
        val isEnPassant: Boolean = false,
        val isCastle: Boolean = false,
        val castleRookFrom: Int = -1,
        val castleRookTo: Int = -1
    )

    class GameState(
        var board: IntArray = initialBoard(),
        var turn: Color = Color.WHITE,
        var castlingRights: Int = 0b1111, // bit0 WK, bit1 WQ, bit2 BK, bit3 BQ
        var enPassantSquare: Int = -1,
        var halfmoveClock: Int = 0,
        var fullmoveNumber: Int = 1,
        var result: GameResult = GameResult.ONGOING,
        var moveHistory: MutableList<Move> = mutableListOf()
    ) {
        fun deepCopy(): GameState = GameState(
            board.copyOf(),
            turn,
            castlingRights,
            enPassantSquare,
            halfmoveClock,
            fullmoveNumber,
            result,
            moveHistory.toMutableList()
        )
    }

    fun initialBoard(): IntArray = intArrayOf(
        -4, -2, -3, -5, -6, -3, -2, -4, // rank 8 (black)
        -1, -1, -1, -1, -1, -1, -1, -1, // rank 7
        0, 0, 0, 0, 0, 0, 0, 0,          // rank 6
        0, 0, 0, 0, 0, 0, 0, 0,          // rank 5
        0, 0, 0, 0, 0, 0, 0, 0,          // rank 4
        0, 0, 0, 0, 0, 0, 0, 0,          // rank 3
        1, 1, 1, 1, 1, 1, 1, 1,          // rank 2
        4, 2, 3, 5, 6, 3, 2, 4           // rank 1 (white)
    )

    fun pieceColor(piece: Int): Color? = when {
        piece > 0 -> Color.WHITE
        piece < 0 -> Color.BLACK
        else -> null
    }

    fun sameSide(piece: Int, color: Color): Boolean = pieceColor(piece) == color

    fun pieceType(piece: Int): Int = abs(piece)

    fun algebraic(index: Int): String {
        val file = 'a' + index % 8
        val rank = '1' + index / 8
        return "$file$rank"
    }

    fun fromAlgebraic(s: String): Int {
        if (s.length < 2) return -1
        val file = s[0] - 'a'
        val rank = s[1] - '1'
        if (file < 0 || file > 7 || rank < 0 || rank > 7) return -1
        return rank * 8 + file
    }

    // ---------- Move generation ----------

    fun generatePseudoLegalMoves(state: GameState, color: Color): List<Move> {
        val moves = mutableListOf<Move>()
        for (i in 0..63) {
            val p = state.board[i]
            if (p == 0 || !sameSide(p, color)) continue
            when (pieceType(p)) {
                Pieces.PAWN -> generatePawnMoves(state, i, color, moves)
                Pieces.KNIGHT -> generateKnightMoves(state, i, color, moves)
                Pieces.BISHOP -> generateSlidingMoves(state, i, color, DIAGONALS, moves)
                Pieces.ROOK -> generateSlidingMoves(state, i, color, ORTHOGONALS, moves)
                Pieces.QUEEN -> {
                    generateSlidingMoves(state, i, color, DIAGONALS, moves)
                    generateSlidingMoves(state, i, color, ORTHOGONALS, moves)
                }
                Pieces.KING -> generateKingMoves(state, i, color, moves)
            }
        }
        moves.addAll(buildCastleMoves(state, color))
        return moves
    }

    private val DIAGONALS = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
    private val ORTHOGONALS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    private fun generatePawnMoves(state: GameState, from: Int, color: Color, moves: MutableList<Move>) {
        val dir = if (color == Color.WHITE) 1 else -1 // rank direction
        val startRank = if (color == Color.WHITE) 1 else 6
        val promotionRank = if (color == Color.WHITE) 7 else 0
        val rank = from / 8
        val file = from % 8

        // Forward pushes
        val oneRank = rank + dir
        if (oneRank in 0..7 && state.board[oneRank * 8 + file] == 0) {
            val to = oneRank * 8 + file
            if (oneRank == promotionRank) {
                addPromotionMoves(from, to, capture = false, color, moves)
            } else {
                moves.add(Move(from, to))
                // Double push from the starting rank
                if (rank == startRank) {
                    val twoRank = rank + 2 * dir
                    if (state.board[twoRank * 8 + file] == 0) {
                        moves.add(Move(from, twoRank * 8 + file))
                    }
                }
            }
        }

        // Diagonal captures (including en passant)
        for (df in intArrayOf(-1, 1)) {
            val nf = file + df
            if (nf < 0 || nf > 7 || oneRank < 0 || oneRank > 7) continue
            val to = oneRank * 8 + nf
            val target = state.board[to]
            if (target != 0 && !sameSide(target, color)) {
                if (oneRank == promotionRank) {
                    addPromotionMoves(from, to, capture = true, color, moves)
                } else {
                    moves.add(Move(from, to, isCapture = true))
                }
            } else if (target == 0 && to == state.enPassantSquare) {
                moves.add(Move(from, to, isCapture = true, isEnPassant = true))
            }
        }
    }

    private fun addPromotionMoves(from: Int, to: Int, capture: Boolean, color: Color, moves: MutableList<Move>) {
        for (piece in intArrayOf(Pieces.QUEEN, Pieces.ROOK, Pieces.BISHOP, Pieces.KNIGHT)) {
            moves.add(Move(from, to, promotion = piece, isCapture = capture))
        }
    }

    private fun generateKnightMoves(state: GameState, from: Int, color: Color, moves: MutableList<Move>) {
        val r = from / 8
        val f = from % 8
        val deltas = listOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1)
        for ((dr, df) in deltas) {
            val nr = r + dr
            val nf = f + df
            if (nr < 0 || nr > 7 || nf < 0 || nf > 7) continue
            val to = nr * 8 + nf
            val target = state.board[to]
            if (target == 0 || !sameSide(target, color)) {
                moves.add(Move(from, to, isCapture = target != 0))
            }
        }
    }

    private fun generateSlidingMoves(state: GameState, from: Int, color: Color, deltas: List<Pair<Int, Int>>, moves: MutableList<Move>) {
        val r = from / 8
        val f = from % 8
        for ((dr, df) in deltas) {
            var nr = r + dr
            var nf = f + df
            while (nr in 0..7 && nf in 0..7) {
                val to = nr * 8 + nf
                val target = state.board[to]
                if (target == 0) {
                    moves.add(Move(from, to))
                } else {
                    if (!sameSide(target, color)) {
                        moves.add(Move(from, to, isCapture = true))
                    }
                    break
                }
                nr += dr
                nf += df
            }
        }
    }

    private fun generateKingMoves(state: GameState, from: Int, color: Color, moves: MutableList<Move>) {
        val r = from / 8
        val f = from % 8
        for (dr in -1..1) {
            for (df in -1..1) {
                if (dr == 0 && df == 0) continue
                val nr = r + dr
                val nf = f + df
                if (nr < 0 || nr > 7 || nf < 0 || nf > 7) continue
                val to = nr * 8 + nf
                val target = state.board[to]
                if (target == 0 || !sameSide(target, color)) {
                    moves.add(Move(from, to, isCapture = target != 0))
                }
            }
        }
    }

    /**
     * Castling moves with full path safety checks (king not in check,
     * intermediate squares not attacked, squares empty, rook present).
     */
    fun buildCastleMoves(state: GameState, color: Color): List<Move> {
        val moves = mutableListOf<Move>()
        val kingSideBit = if (color == Color.WHITE) 0b0001 else 0b0100
        val queenSideBit = if (color == Color.WHITE) 0b0010 else 0b1000
        val homeRank = if (color == Color.WHITE) 0 else 7
        val rookSign = if (color == Color.WHITE) 1 else -1

        val kingFrom = homeRank * 8 + 4
        if (state.board[kingFrom] != rookSign * Pieces.KING) return moves

        // King side
        if ((state.castlingRights and kingSideBit) != 0) {
            val f = homeRank * 8
            val rookAt = f + 7
            if (state.board[rookAt] == rookSign * Pieces.ROOK &&
                state.board[f + 5] == 0 && state.board[f + 6] == 0 &&
                !isInCheck(state, color) &&
                !isSquareAttacked(state, f + 5, opposite(color)) &&
                !isSquareAttacked(state, f + 6, opposite(color))
            ) {
                moves.add(Move(kingFrom, f + 6, isCastle = true, castleRookFrom = rookAt, castleRookTo = f + 5))
            }
        }
        // Queen side
        if ((state.castlingRights and queenSideBit) != 0) {
            val f = homeRank * 8
            val rookAt = f
            if (state.board[rookAt] == rookSign * Pieces.ROOK &&
                state.board[f + 1] == 0 && state.board[f + 2] == 0 && state.board[f + 3] == 0 &&
                !isInCheck(state, color) &&
                !isSquareAttacked(state, f + 3, opposite(color)) &&
                !isSquareAttacked(state, f + 2, opposite(color))
            ) {
                moves.add(Move(kingFrom, f + 2, isCastle = true, castleRookFrom = rookAt, castleRookTo = f + 3))
            }
        }
        return moves
    }

    fun opposite(color: Color): Color = if (color == Color.WHITE) Color.BLACK else Color.WHITE

    fun isSquareAttacked(state: GameState, square: Int, byColor: Color): Boolean =
        generatePseudoLegalMoves(state, byColor).any { it.to == square }

    fun isInCheck(state: GameState, color: Color): Boolean {
        val kingIdx = state.board.indexOfFirst {
            pieceType(it) == Pieces.KING && sameSide(it, color)
        }
        if (kingIdx == -1) return false
        return generatePseudoLegalMoves(state, opposite(color)).any { it.to == kingIdx }
    }

    // ---------- Applying moves ----------

    fun generateLegalMoves(state: GameState): List<Move> {
        val color = state.turn
        return generatePseudoLegalMoves(state, color)
            .filter { !leavesKingInCheck(state, it) }
    }

    fun leavesKingInCheck(state: GameState, move: Move): Boolean {
        val copy = state.deepCopy()
        applyMoveInternal(copy, move, updateResult = false)
        return isInCheck(copy, state.turn)
    }

    fun applyMoveInternal(state: GameState, move: Move, updateResult: Boolean): GameState {
        val piece = state.board[move.from]
        val color = pieceColor(piece) ?: state.turn

        // En passant: remove the pawn behind the target square
        if (move.isEnPassant) {
            val capIdx = if (color == Color.WHITE) move.to - 8 else move.to + 8
            state.board[capIdx] = 0
        }

        // Move the piece (with promotion)
        state.board[move.to] = if (move.promotion != 0) {
            if (color == Color.WHITE) move.promotion else -move.promotion
        } else {
            piece
        }
        state.board[move.from] = 0

        // Castling: also move the rook
        if (move.isCastle && move.castleRookFrom >= 0) {
            state.board[move.castleRookTo] = state.board[move.castleRookFrom]
            state.board[move.castleRookFrom] = 0
        }

        // En passant target square for next move
        state.enPassantSquare =
            if (pieceType(piece) == Pieces.PAWN && abs(move.to - move.from) == 16) {
                (move.from + move.to) / 2
            } else -1

        updateCastlingRights(state, move)

        // Clocks
        if (pieceType(piece) == Pieces.PAWN || move.isCapture) {
            state.halfmoveClock = 0
        } else {
            state.halfmoveClock++
        }
        if (color == Color.BLACK) state.fullmoveNumber++

        state.moveHistory.add(move)
        state.turn = opposite(color)

        if (updateResult) updateGameResult(state)
        return state
    }

    private fun updateCastlingRights(state: GameState, move: Move) {
        // King moved: clear that side's rights
        when (move.from) {
            4 -> state.castlingRights = state.castlingRights and 0b1100.inv()
            60 -> state.castlingRights = state.castlingRights and 0b0011.inv()
        }
        // Rook moved or captured on a corner square
        for (corner in listOf(move.from, move.to)) {
            when (corner) {
                0 -> state.castlingRights = state.castlingRights and 0b1101.inv() // a1
                7 -> state.castlingRights = state.castlingRights and 0b1110.inv() // h1
                56 -> state.castlingRights = state.castlingRights and 0b0111.inv() // a8
                63 -> state.castlingRights = state.castlingRights and 0b1011.inv() // h8
            }
        }
    }

    fun updateGameResult(state: GameState) {
        if (state.result != GameResult.ONGOING) return
        val legal = generateLegalMoves(state)
        state.result = when {
            legal.isEmpty() && isInCheck(state, state.turn) ->
                if (state.turn == Color.WHITE) GameResult.BLACK_WIN else GameResult.WHITE_WIN
            legal.isEmpty() -> GameResult.DRAW // stalemate
            state.halfmoveClock >= 100 -> GameResult.DRAW // 50-move rule
            else -> GameResult.ONGOING
        }
    }

    /** Validate and apply a move. Returns false if illegal. */
    fun applyMove(state: GameState, move: Move): Boolean {
        if (state.result != GameResult.ONGOING) return false
        val legal = generateLegalMoves(state)
        val match = legal.firstOrNull {
            it.from == move.from && it.to == move.to && it.promotion == move.promotion
        } ?: return false
        applyMoveInternal(state, match, updateResult = true)
        return true
    }

    fun checkAndBuildMove(state: GameState, from: Int, to: Int, promotion: Int): Move? =
        generateLegalMoves(state).firstOrNull {
            it.from == from && it.to == to && it.promotion == promotion
        }

    /** Simple ASCII rendering of the board, for logs and tests. */
    fun boardToString(board: IntArray): String {
        val symbols = mapOf(
            1 to "P", 2 to "N", 3 to "B", 4 to "R", 5 to "Q", 6 to "K",
            -1 to "p", -2 to "n", -3 to "b", -4 to "r", -5 to "q", -6 to "k"
        )
        val sb = StringBuilder()
        for (r in 7 downTo 0) {
            for (f in 0..7) {
                val p = board[r * 8 + f]
                sb.append(symbols[p] ?: ".")
            }
            sb.append('\n')
        }
        return sb.toString()
    }
}
