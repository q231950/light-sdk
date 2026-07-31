package dev.neoneon.flamingo

import dev.neoneon.chesskit.Board
import dev.neoneon.chesskit.Piece
import dev.neoneon.chesskit.Position
import dev.neoneon.chesskit.Square
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CheckHighlightTest {

    @Test
    fun marksTheCheckedKingsSquare() {
        // Rg1-h1+ checks the black king on h8 down the h-file.
        val board = Board(Position("7k/8/8/8/8/8/8/K5R1 w - - 0 1")!!)
        board.move(pieceAt = Square.g1, to = Square.h1)

        assertEquals(Board.State.check(Piece.Color.black), board.state)
        assertEquals(Square.h8, checkedKingSquare(board.state, board.position))
    }

    @Test
    fun marksTheMatedKingsSquare() {
        // Back-rank mate: Ra1-a8#, with the black king on g8 boxed in by its own f7/g7/h7 pawns.
        val board = Board(Position("6k1/5ppp/8/8/8/8/8/R6K w - - 0 1")!!)
        board.move(pieceAt = Square.a1, to = Square.a8)

        assertEquals(Board.State.checkmate(Piece.Color.black), board.state)
        assertEquals(Square.g8, checkedKingSquare(board.state, board.position))
    }

    @Test
    fun marksNothingWhenNoKingIsInCheck() {
        val board = Board()
        board.move(pieceAt = Square.e2, to = Square.e4)

        assertNull(checkedKingSquare(board.state, board.position))
    }

    @Test
    fun marksTheCheckedKingAfterReplayingRecordedMoves() {
        // How GameViewViewModel rebuilds an in-progress game: a fresh Board replaying each stored
        // move's LAN (see replayMove). Pins that the marker survives that path — Board only
        // evaluates the right king when its state came from a move, so a board whose position was
        // set wholesale instead would report no check at all.
        val board = Board()
        for (lan in listOf("f2f3", "e7e5", "g2g4", "d8h4")) {
            board.move(pieceAt = Square(lan.substring(0, 2)), to = Square(lan.substring(2, 4)))
        }

        // 1. f3 e5 2. g4 Qh4# — mate on the white king, still on e1.
        assertEquals(Board.State.checkmate(Piece.Color.white), board.state)
        assertEquals(Square.e1, checkedKingSquare(board.state, board.position))
    }
}
