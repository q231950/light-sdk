package dev.neoneon.flamingo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import dev.neoneon.chesskit.Piece
import dev.neoneon.chesskit.Position
import dev.neoneon.chesskit.Square

/**
 * Interactive chess board rendering a chesskit [Position] with tap-to-select/tap-to-move squares.
 *
 * [orientation] is the local player's color: their pieces sit at the bottom, so a black
 * player sees the board flipped (rank 1 on top, files h→a left-to-right).
 *
 * [boardSize] is the full side length of the (square) board; each square is [boardSize] / 8. The
 * caller sizes it to fit the space actually available (see GameView), so it never overflows the
 * screen regardless of device proportions.
 */
@Composable
fun ChessBoard(
    position: Position,
    selectedSquare: Square?,
    legalTargets: Set<Square>,
    onSquareTap: (Square) -> Unit,
    boardSize: Dp,
    modifier: Modifier = Modifier,
    orientation: Piece.Color = Piece.Color.white,
) {
    val squareSize = boardSize / 8
    val ranks = if (orientation == Piece.Color.black) (1..8) else (8 downTo 1)
    val files = if (orientation == Piece.Color.black) {
        Square.File.entries.reversed()
    } else {
        Square.File.entries
    }
    Column(modifier = modifier) {
        for (rankValue in ranks) {
            Row {
                for (file in files) {
                    val square = Square(file, Square.Rank(rankValue))
                    ChessSquare(
                        piece = position.piece(at = square),
                        isDark = square.color == Square.Color.dark,
                        isSelected = square == selectedSquare,
                        isLegalTarget = square in legalTargets,
                        squareSize = squareSize,
                        onTap = { onSquareTap(square) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChessSquare(
    piece: Piece?,
    isDark: Boolean,
    isSelected: Boolean,
    isLegalTarget: Boolean,
    squareSize: Dp,
    onTap: () -> Unit,
) {
    val content = LightThemeTokens.colors.content
    val backgroundAlpha = when {
        isSelected -> 0.35f
        isLegalTarget -> 0.25f
        isDark -> 0.12f
        else -> 0.03f
    }

    Box(
        modifier = Modifier
            .size(squareSize)
            .background(content.copy(alpha = backgroundAlpha))
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        if (piece != null) {
            LightText(
                text = piece.copy(color = piece.color.opposite).graphic,
                variant = LightTextVariant.Heading,
                align = TextAlign.Center,
            )
        }
    }
}
