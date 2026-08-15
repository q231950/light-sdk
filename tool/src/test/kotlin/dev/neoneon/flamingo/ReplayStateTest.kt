package dev.neoneon.flamingo

import dev.neoneon.chesskit.Piece
import dev.neoneon.chesskit.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the game screen offers once a game is over: stepping back through the positions it passed
 * through, rather than sitting on the closing one with nothing to do.
 */
class ReplayStateTest {

    private fun state(
        outcome: GameOutcome? = null,
        frameIndex: Int = 0,
        frameCount: Int = 0,
    ) = GameViewViewModel.State(
        position = Position.standard,
        localColor = Piece.Color.white,
        outcome = outcome,
        frameIndex = frameIndex,
        frameCount = frameCount,
    )

    private val checkmate = GameOutcome.Checkmate(Piece.Color.white)

    @Test
    fun aGameStillBeingPlayedHasNothingToReplay() {
        // frameCount is 0 while the game is on: the positions are recorded, but offering to step
        // through a game you are still playing would just be a way to lose your place in it.
        assertFalse(state(outcome = null, frameCount = 0).canReplay)
    }

    @Test
    fun aFinishedGameWithMovesCanBeSteppedThrough() {
        assertTrue(state(outcome = checkmate, frameIndex = 7, frameCount = 8).canReplay)
    }

    @Test
    fun aGameThatEndedOnItsOpeningPositionHasNothingToStepThrough() {
        // One frame is the opening position alone — a game resigned before a piece moved. There is
        // no second position to step to, so the bar stays off.
        assertFalse(state(outcome = GameOutcome.Resigned(Piece.Color.black), frameCount = 1).canReplay)
    }

    @Test
    fun theLabelNamesTheOpeningPositionRatherThanMoveZero() {
        assertEquals("Start", state(outcome = checkmate, frameIndex = 0, frameCount = 8).replayLabel)
    }

    @Test
    fun theLabelCountsMovesNotPositions() {
        // 8 frames are the opening plus 7 moves, so the last one is move 7 — not move 8, and not
        // "7 of 8".
        assertEquals("Move 7 of 7", state(outcome = checkmate, frameIndex = 7, frameCount = 8).replayLabel)
        assertEquals("Move 3 of 7", state(outcome = checkmate, frameIndex = 3, frameCount = 8).replayLabel)
    }
}
