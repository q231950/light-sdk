package dev.neoneon.flamingo

import dev.neoneon.chesskit.Piece
import dev.neoneon.chesskit.Square
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where the last-move arrow's endpoints land. The arrow is the one board marking that spans two
 * squares, so unlike the check underline it can't inherit its position from the square it belongs
 * to — it computes screen coordinates, which is exactly where a flipped board goes wrong.
 */
class LastMoveArrowGeometryTest {

    // One square per 10 units keeps the expected centers readable: a1's center is (5, 75) for
    // white, i.e. first column, last row.
    private val squareSize = 10f

    private fun center(square: Square, orientation: Piece.Color) = squareCenter(
        square = square,
        fileOrder = boardFileOrder(orientation),
        rankOrder = boardRankOrder(orientation),
        squareSize = squareSize,
    )

    @Test
    fun placesSquaresWithWhitesBackRankAtTheBottom() {
        val a1 = center(Square.a1, Piece.Color.white)
        assertEquals(5f, a1.x)
        assertEquals(75f, a1.y)

        val h8 = center(Square.h8, Piece.Color.white)
        assertEquals(75f, h8.x)
        assertEquals(5f, h8.y)
    }

    @Test
    fun flipsBothAxesForABlackPlayer() {
        // Black sees their own back rank nearest them and the files right-to-left, so a1 — the
        // far corner from black's seat — moves to the top right.
        val a1 = center(Square.a1, Piece.Color.black)
        assertEquals(75f, a1.x)
        assertEquals(5f, a1.y)

        val h8 = center(Square.h8, Piece.Color.black)
        assertEquals(5f, h8.x)
        assertEquals(75f, h8.y)
    }

    @Test
    fun pointsAPawnPushAwayFromTheMovingPlayer() {
        // e2-e4 runs up the screen for white and down it for black: the arrow always leads away
        // from whoever played it.
        val whiteStart = center(Square.e2, Piece.Color.white)
        val whiteEnd = center(Square.e4, Piece.Color.white)
        assertEquals(whiteStart.x, whiteEnd.x)
        assertTrue(whiteEnd.y < whiteStart.y, "white's pawn should advance up the screen")

        val blackStart = center(Square.e2, Piece.Color.black)
        val blackEnd = center(Square.e4, Piece.Color.black)
        assertEquals(blackStart.x, blackEnd.x)
        assertTrue(blackEnd.y > blackStart.y, "seen from black, the same push comes down the screen")
    }

    @Test
    fun keepsEverySquareInsideTheBoard() {
        for (orientation in Piece.Color.entries) {
            for (square in Square.entries) {
                val center = center(square, orientation)
                assertTrue(center.x in 5f..75f && center.y in 5f..75f, "$square escaped the board")
            }
        }
    }
}
