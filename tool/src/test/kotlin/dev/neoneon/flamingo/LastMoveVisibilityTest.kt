package dev.neoneon.flamingo

import dev.neoneon.chesskit.Board
import dev.neoneon.chesskit.Piece
import dev.neoneon.chesskit.Square
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LastMoveVisibilityTest {

    @Test
    fun defaultsToOpponentOnlyWhenNothingIsStored() {
        assertEquals(LastMoveVisibility.OpponentOnly, parseLastMoveVisibility(null))
    }

    @Test
    fun defaultsToOpponentOnlyForAnUnrecognizedValue() {
        assertEquals(LastMoveVisibility.OpponentOnly, parseLastMoveVisibility("not-a-real-setting"))
    }

    @Test
    fun roundTripsEveryEntryByName() {
        for (value in LastMoveVisibility.entries) {
            assertEquals(value, parseLastMoveVisibility(value.name))
        }
    }

    @Test
    fun hidesRegardlessOfWhoMoved() {
        val board = Board()
        val move = board.move(pieceAt = Square.e2, to = Square.e4)!!
        val lastMove = move.start to move.end

        assertFalse(
            shouldShowLiveLastMove(LastMoveVisibility.Hidden, lastMove, board.position, Piece.Color.white),
        )
        assertFalse(
            shouldShowLiveLastMove(LastMoveVisibility.Hidden, lastMove, board.position, Piece.Color.black),
        )
    }

    @Test
    fun showsLatestRegardlessOfWhoMoved() {
        val board = Board()
        val move = board.move(pieceAt = Square.e2, to = Square.e4)!!
        val lastMove = move.start to move.end

        assertTrue(
            shouldShowLiveLastMove(LastMoveVisibility.Latest, lastMove, board.position, Piece.Color.white),
        )
        assertTrue(
            shouldShowLiveLastMove(LastMoveVisibility.Latest, lastMove, board.position, Piece.Color.black),
        )
    }

    @Test
    fun opponentOnlyHidesOurOwnMoveAndShowsTheirs() {
        // White just played e2-e4.
        val board = Board()
        val move = board.move(pieceAt = Square.e2, to = Square.e4)!!
        val lastMove = move.start to move.end

        // Seen as white, that was our own move — nothing to point out.
        assertFalse(
            shouldShowLiveLastMove(LastMoveVisibility.OpponentOnly, lastMove, board.position, Piece.Color.white),
        )
        // Seen as black, that was the opponent's move — worth marking.
        assertTrue(
            shouldShowLiveLastMove(LastMoveVisibility.OpponentOnly, lastMove, board.position, Piece.Color.black),
        )
    }

    @Test
    fun hidesWhenThereIsNoLastMoveYet() {
        val board = Board()
        for (visibility in LastMoveVisibility.entries) {
            assertFalse(shouldShowLiveLastMove(visibility, null, board.position, Piece.Color.white))
        }
    }
}
