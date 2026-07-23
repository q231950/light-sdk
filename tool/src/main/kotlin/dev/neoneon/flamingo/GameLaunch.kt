package dev.neoneon.flamingo

import dev.neoneon.chesskit.Piece

/**
 * The result the create / join flows hand back to the games list: which game to
 * open in [GameView] and which color this device plays. The list pops the flow
 * screen and pushes [GameView] with these, so back from the game lands on the list
 * rather than the (now finished) create/join screen.
 */
data class NewGameDestination(
    val gameId: String,
    val color: Piece.Color,
)
