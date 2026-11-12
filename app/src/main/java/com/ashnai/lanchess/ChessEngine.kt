package com.ashnai.lanchess

/**
 * Pure Kotlin chess rules engine with no Android dependencies.
 * Board is an IntArray(64); a1=0 ... h1=7, a8=56 ... h8=63.
 * Encoding: piece type in low 3 bits, BLACK_FLAG (8) ORed in for black pieces.
 */
object ChessEngine {

    const val EMPTY = 0
    const val PAWN = 1
    const val KNIGHT = 2
    const val BISHOP = 3
    const val ROOK = 4
    const val QUEEN = 5
    const val KING = 6
    const val BLACK_FLAG = 8

    const val WHITE = 0
    const val BLACK = 1

    // Castling right bits.
    private const val WK = 1
    private const val WQ = 2
    private const val BK = 4
    private const val BQ = 8

    enum class Status { ACTIVE, CHECK, CHECKMATE, STALEMATE, DRAW_HALFMOVE, DRAW_INSUFFICIENT }

    class GameState(
        val board: IntArray,
        val turn: Int,
        val castling: Int,
        val enPassant: Int,
        val halfmoveClock: Int,
        val fullmoveNumber: Int
    ) {
        override fun equals(other: Any?): Boolean =
            other is GameState && other.board.contentEquals(board) &&
                other.turn == turn && other.castling == castling &&
                other.enPassant == enPassant
        override fun hashCode(): Int = board.contentHashCode() * 31 + turn
    }

    private val FILES = "abcdefgh"
    private val RANKS = "12345678"

    // (rank, file) unit deltas — rank/file arithmetic avoids all board-wrap bugs.
    private val QUEEN_DIRS = arrayOf(
        intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1),
        intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1)
    )
    private val ROOK_DIRS = arrayOf(
        intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1)
    )
    private val BISHOP_DIRS = arrayOf(
        intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1)
    )
    private val KNIGHT_JUMPS = arrayOf(
        intArrayOf(1, 2), intArrayOf(2, 1), intArrayOf(2, -1), intArrayOf(1, -2),
        intArrayOf(-1, -2), intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, 2)
    )

    private fun flag(side: Int): Int = if (side == WHITE) 0 else BLACK_FLAG

    fun initialPosition(): GameState {
        val b = IntArray(64)
        val backRank = intArrayOf(ROOK, KNIGHT, BISHOP, QUEEN, KING, BISHOP, KNIGHT, ROOK)
        for (f in 0..7) {
            b[f] = backRank[f]
            b[8 + f] = PAWN
            b[48 + f] = PAWN or BLACK_FLAG
            b[56 + f] = backRank[f] or BLACK_FLAG
        }
        return GameState(b, WHITE, WK or WQ or BK or BQ, -1, 0, 1)
    }

    fun moveToText(from: Int, to: Int, promo: Int): String {
        val sb = StringBuilder()
        sb.append(FILES[from % 8]); sb.append(RANKS[from / 8])
        sb.append(FILES[to % 8]); sb.append(RANKS[to / 8])
        if (promo != EMPTY) sb.append(
            when (promo) { QUEEN -> "q"; ROOK -> "r"; BISHOP -> "b"; else -> "n" }
        )
        return sb.toString()
    }

    fun textToMove(s: String): Triple<Int, Int, Int>? {
        if (s.length < 4) return null
        val f = FILES.indexOf(s[0]); val r = RANKS.indexOf(s[1])
        val f2 = FILES.indexOf(s[2]); val r2 = RANKS.indexOf(s[3])
        if (f < 0 || r < 0 || f2 < 0 || r2 < 0) return null
        val promo = when (if (s.length > 4) s[4] else ' ') {
            'q' -> QUEEN; 'r' -> ROOK; 'b' -> BISHOP; 'n' -> KNIGHT; else -> EMPTY
        }
        return Triple(r * 8 + f, r2 * 8 + f2, promo)
    }

    // ------------------------------------------------------------------
    // Move generation
    // ------------------------------------------------------------------

    fun generateMoves(state: GameState, side: Int): List<Triple<Int, Int, Int>> {
        val moves = ArrayList<Triple<Int, Int, Int>>(64)
        for (sq in 0..63) {
            val piece = state.board[sq]
            if (piece == EMPTY || BoardUtils.pieceColor(piece) != side) continue
            when (BoardUtils.pieceType(piece)) {
                PAWN -> pawnMoves(state, sq, side, moves)
                KNIGHT -> leaperMoves(state, sq, side, KNIGHT_JUMPS, moves)
                BISHOP -> sliderMoves(state, sq, side, BISHOP_DIRS, moves)
                ROOK -> sliderMoves(state, sq, side, ROOK_DIRS, moves)
                QUEEN -> sliderMoves(state, sq, side, QUEEN_DIRS, moves)
                KING -> kingMoves(state, sq, side, moves)
            }
        }
        return moves
    }

    fun legalMoves(state: GameState, side: Int): List<Triple<Int, Int, Int>> =
        generateMoves(state, side).filter { m ->
            !isInCheck(applyMove(state, m.first, m.second, m.third), side)
        }

    private fun pawnMoves(state: GameState, sq: Int, side: Int, out: MutableList<Triple<Int, Int, Int>>) {
        val step = if (side == WHITE) 1 else -1
        val startRank = if (side == WHITE) 1 else 6
        val promoRank = if (side == WHITE) 7 else 0
        val rank = sq / 8
        val file = sq % 8

        val oneR = rank + step
        if (oneR in 0..7) {
            val one = oneR * 8 + file
            if (state.board[one] == EMPTY) {
                addPawnMove(sq, one, oneR, promoRank, out)
                if (rank == startRank) {
                    val twoR = rank + 2 * step
                    val two = twoR * 8 + file
                    if (state.board[two] == EMPTY) out.add(Triple(sq, two, EMPTY))
                }
            }
            for (df in intArrayOf(-1, 1)) {
                val nf = file + df
                if (nf in 0..7) {
                    val to = oneR * 8 + nf
                    val target = state.board[to]
                    if (target != EMPTY && BoardUtils.pieceColor(target) != side) {
                        addPawnMove(sq, to, oneR, promoRank, out)
                    } else if (target == EMPTY && to == state.enPassant) {
                        out.add(Triple(sq, to, EMPTY))
                    }
                }
            }
        }
    }

    private fun addPawnMove(sq: Int, to: Int, toRank: Int, promoRank: Int, out: MutableList<Triple<Int, Int, Int>>) {
        if (toRank == promoRank) {
            out.add(Triple(sq, to, QUEEN))
            out.add(Triple(sq, to, ROOK))
            out.add(Triple(sq, to, BISHOP))
            out.add(Triple(sq, to, KNIGHT))
        } else {
            out.add(Triple(sq, to, EMPTY))
        }
    }

    private fun leaperMoves(state: GameState, sq: Int, side: Int, deltas: Array<IntArray>, out: MutableList<Triple<Int, Int, Int>>) {
        val r = sq / 8
        val f = sq % 8
        for (d in deltas) {
            val nr = r + d[0]
            val nf = f + d[1]
            if (nr in 0..7 && nf in 0..7) {
                val to = nr * 8 + nf
                val target = state.board[to]
                if (target == EMPTY || BoardUtils.pieceColor(target) != side) {
                    out.add(Triple(sq, to, EMPTY))
                }
            }
        }
    }

    private fun sliderMoves(state: GameState, sq: Int, side: Int, dirs: Array<IntArray>, out: MutableList<Triple<Int, Int, Int>>) {
        val r = sq / 8
        val f = sq % 8
        for (d in dirs) {
            var nr = r + d[0]
            var nf = f + d[1]
            while (nr in 0..7 && nf in 0..7) {
                val to = nr * 8 + nf
                val target = state.board[to]
                if (target == EMPTY) {
                    out.add(Triple(sq, to, EMPTY))
                } else {
                    if (BoardUtils.pieceColor(target) != side) out.add(Triple(sq, to, EMPTY))
                    break
                }
                nr += d[0]
                nf += d[1]
            }
        }
    }

    private fun kingMoves(state: GameState, sq: Int, side: Int, out: MutableList<Triple<Int, Int, Int>>) {
        val r = sq / 8
        val f = sq % 8
        for (d in QUEEN_DIRS) {
            val nr = r + d[0]
            val nf = f + d[1]
            if (nr in 0..7 && nf in 0..7) {
                val to = nr * 8 + nf
                val target = state.board[to]
                if (target == EMPTY || BoardUtils.pieceColor(target) != side) {
                    out.add(Triple(sq, to, EMPTY))
                }
            }
        }
        // Castling. King must be on its home square, not in check, path clear and safe.
        val home = if (side == WHITE) 4 else 60
        if (sq != home || isInCheck(state, side)) return
        val kingBit = if (side == WHITE) WK else BK
        val queenBit = if (side == WHITE) WQ else BQ
        val rookPiece = ROOK or flag(side)
        val rank = if (side == WHITE) 0 else 7
        if (state.castling and kingBit != 0 &&
            state.board[rank * 8 + 5] == EMPTY && state.board[rank * 8 + 6] == EMPTY &&
            state.board[rank * 8 + 7] == rookPiece &&
            !attacked(state, rank * 8 + 5, 1 - side) && !attacked(state, rank * 8 + 6, 1 - side)
        ) {
            out.add(Triple(sq, rank * 8 + 6, EMPTY))
        }
        if (state.castling and queenBit != 0 &&
            state.board[rank * 8 + 3] == EMPTY && state.board[rank * 8 + 2] == EMPTY &&
            state.board[rank * 8 + 1] == EMPTY &&
            state.board[rank * 8 + 0] == rookPiece &&
            !attacked(state, rank * 8 + 3, 1 - side) && !attacked(state, rank * 8 + 2, 1 - side)
        ) {
            out.add(Triple(sq, rank * 8 + 2, EMPTY))
        }
    }

    // ------------------------------------------------------------------
    // Attack detection
    // ------------------------------------------------------------------

    /** True if [sq] is attacked by any piece of [by]. */
    fun attacked(state: GameState, sq: Int, by: Int): Boolean {
        val b = state.board
        val r = sq / 8
        val f = sq % 8

        // Pawn: an attacking pawn sits one rank "behind" sq from its own direction.
        val pawnRank = r - (if (by == WHITE) 1 else -1)
        if (pawnRank in 0..7) {
            val pawnPiece = PAWN or flag(by)
            if (f > 0 && b[pawnRank * 8 + f - 1] == pawnPiece) return true
            if (f < 7 && b[pawnRank * 8 + f + 1] == pawnPiece) return true
        }
        for (d in KNIGHT_JUMPS) {
            val nr = r + d[0]
            val nf = f + d[1]
            if (nr in 0..7 && nf in 0..7 && b[nr * 8 + nf] == KNIGHT or flag(by)) return true
        }
        for (d in QUEEN_DIRS) {
            val nr = r + d[0]
            val nf = f + d[1]
            if (nr in 0..7 && nf in 0..7 && b[nr * 8 + nf] == KING or flag(by)) return true
        }
        for (d in ROOK_DIRS) {
            var nr = r + d[0]
            var nf = f + d[1]
            while (nr in 0..7 && nf in 0..7) {
                val p = b[nr * 8 + nf]
                if (p != EMPTY) {
                    if (p == ROOK or flag(by) || p == QUEEN or flag(by)) return true
                    break
                }
                nr += d[0]
                nf += d[1]
            }
        }
        for (d in BISHOP_DIRS) {
            var nr = r + d[0]
            var nf = f + d[1]
            while (nr in 0..7 && nf in 0..7) {
                val p = b[nr * 8 + nf]
                if (p != EMPTY) {
                    if (p == BISHOP or flag(by) || p == QUEEN or flag(by)) return true
                    break
                }
                nr += d[0]
                nf += d[1]
            }
        }
        return false
    }

    fun isInCheck(state: GameState, side: Int): Boolean {
        val kingPiece = KING or flag(side)
        for (sq in 0..63) {
            if (state.board[sq] == kingPiece) return attacked(state, sq, 1 - side)
        }
        return false
    }

    // ------------------------------------------------------------------
    // Applying moves
    // ------------------------------------------------------------------

    fun applyMove(state: GameState, from: Int, to: Int, promo: Int): GameState {
        val b = state.board.copyOf()
        val piece = b[from]
        val side = BoardUtils.pieceColor(piece)
        val type = BoardUtils.pieceType(piece)
        val captured = b[to]

        b[from] = EMPTY
        var newEnPassant = -1
        var castling = state.castling
        var half = state.halfmoveClock + 1
        if (captured != EMPTY) half = 0

        if (type == PAWN) {
            half = 0
            // En passant capture: diagonal move onto the en passant target square.
            if (to == state.enPassant && to % 8 != from % 8) {
                b[to + (if (side == WHITE) -8 else 8)] = EMPTY
            }
            if (promo != EMPTY) {
                b[to] = promo or flag(side)
            } else {
                b[to] = piece
            }
            if (kotlin.math.abs(to - from) == 16) newEnPassant = (from + to) / 2
        } else {
            b[to] = piece
        }

        // Castling: move the rook too.
        if (type == KING && kotlin.math.abs(to - from) == 2) {
            val rank = from / 8
            if (to % 8 == 6) {
                b[rank * 8 + 5] = b[rank * 8 + 7]
                b[rank * 8 + 7] = EMPTY
            } else {
                b[rank * 8 + 3] = b[rank * 8 + 0]
                b[rank * 8 + 0] = EMPTY
            }
        }

        when (from) {
            4 -> castling = castling and (WK or WQ).inv()
            60 -> castling = castling and (BK or BQ).inv()
            0 -> castling = castling and WQ.inv()
            7 -> castling = castling and WK.inv()
            56 -> castling = castling and BQ.inv()
            63 -> castling = castling and BK.inv()
        }
        when (to) {
            0 -> castling = castling and WQ.inv()
            7 -> castling = castling and WK.inv()
            56 -> castling = castling and BQ.inv()
            63 -> castling = castling and BK.inv()
        }

        val fullmove = if (side == BLACK) state.fullmoveNumber + 1 else state.fullmoveNumber
        return GameState(b, 1 - side, castling, newEnPassant, half, fullmove)
    }

    // ------------------------------------------------------------------
    // Game status
    // ------------------------------------------------------------------

    fun status(state: GameState): Status {
        val side = state.turn
        if (legalMoves(state, side).isEmpty()) {
            return if (isInCheck(state, side)) Status.CHECKMATE else Status.STALEMATE
        }
        if (state.halfmoveClock >= 100) return Status.DRAW_HALFMOVE
        if (insufficientMaterial(state)) return Status.DRAW_INSUFFICIENT
        return if (isInCheck(state, side)) Status.CHECK else Status.ACTIVE
    }

    private fun insufficientMaterial(state: GameState): Boolean {
        var minor = 0
        for (p in state.board) {
            when (BoardUtils.pieceType(p)) {
                PAWN, ROOK, QUEEN -> return false
                BISHOP, KNIGHT -> minor++
                else -> {}
            }
        }
        return minor <= 1
    }
}
