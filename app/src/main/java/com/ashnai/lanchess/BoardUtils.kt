package com.ashnai.lanchess

/** Board utilities shared by engine, UI and protocol layer. */
object BoardUtils {

    fun pieceType(piece: Int): Int = piece and 7
    fun pieceColor(piece: Int): Int = if (piece and ChessEngine.BLACK_FLAG == 0) ChessEngine.WHITE else ChessEngine.BLACK
    fun isEmpty(piece: Int): Boolean = piece == ChessEngine.EMPTY

    /** Unicode glyphs used by the custom-drawn board view. */
    fun pieceGlyph(piece: Int): String = when (piece) {
        ChessEngine.PAWN -> "\u2659"
        ChessEngine.PAWN or ChessEngine.BLACK_FLAG -> "\u265F"
        ChessEngine.KNIGHT -> "\u2658"
        ChessEngine.KNIGHT or ChessEngine.BLACK_FLAG -> "\u265E"
        ChessEngine.BISHOP -> "\u2657"
        ChessEngine.BISHOP or ChessEngine.BLACK_FLAG -> "\u265D"
        ChessEngine.ROOK -> "\u2656"
        ChessEngine.ROOK or ChessEngine.BLACK_FLAG -> "\u265C"
        ChessEngine.QUEEN -> "\u2655"
        ChessEngine.QUEEN or ChessEngine.BLACK_FLAG -> "\u265B"
        ChessEngine.KING -> "\u2654"
        ChessEngine.KING or ChessEngine.BLACK_FLAG -> "\u265A"
        else -> ""
    }

    fun isDarkSquare(square: Int): Boolean = ((square / 8) + (square % 8)) % 2 == 1

    fun squareName(sq: Int): String = "${"abcdefgh"[sq % 8]}${sq / 8 + 1}"
}
