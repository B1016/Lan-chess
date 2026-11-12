package com.ashnai.lanchess

/**
 * Pure Kotlin chess rules engine with no Android dependencies.
 * Board is an IntArray(64); a1=0 ... h1=7, a8=56 ... h8=63 (rank+file indices).
 * Encoding: piece type in low 3 bits, color flag in bit 4 (0 = White, 1 = Black).
 */
object ChessEngine {

    const val EMPTY = 0
    const val PAWN = 1
    const val KNIGHT = 2
    const val BISHOP = 3
    const val ROOK = 4
    const val QUEEN = 5
    const val KING = 6
    const val BLACK_FLAG = 8 // bit flag ORed into piece type

    const val WHITE = 0
    const val BLACK = 1

    /** Immutable snapshot of a game state used across the LAN protocol and the UI. */
    class GameState(
        val board: IntArray,
        val turn: Int,
        val castling: Int,           // bits: 1 = WK, 2 = WQ, 4 = BK, 8 = BQ
        val enPassant: Int,          // target square index or -1
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

    /** Human readable move in coordinate form, e.g. "e2e4", "e7e8q". */
    fun moveToText(from: Int, to: Int, promo: Int): String {
        val sb = StringBuilder()
        sb.append(FILES[from % 8])
        sb.append(RANKS[from / 8])
        sb.append(FILES[to % 8])
        sb.append(RANKS[to / 8])
        if (promo != EMPTY) sb.append(
            when (promo) {
                QUEEN -> "q"; ROOK -> "r"; BISHOP -> "b"; else -> "n"
            }
        )
        return sb.toString()
    }

    /** Parses "e2e4"/"e7e8q" back into (from, to, promo). Returns null if malformed. */
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
}
