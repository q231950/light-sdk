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
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import dev.neoneon.chesskit.Piece
import dev.neoneon.chesskit.Position
import dev.neoneon.chesskit.Square

private const val BOARD_WIDTH_UNITS = 27f
private const val SQUARE_SIZE_UNITS = BOARD_WIDTH_UNITS / 8f

/**
 * Interactive chess board rendering a chesskit [Position] with tap-to-select/tap-to-move squares.
 *
 * [orientation] is the local player's color: their pieces sit at the bottom, so a black
 * player sees the board flipped (rank 1 on top, files h→a left-to-right).
 */
@Composable
fun ChessBoard(
    position: Position,
    selectedSquare: Square?,
    legalTargets: Set<Square>,
    onSquareTap: (Square) -> Unit,
    modifier: Modifier = Modifier,
    orientation: Piece.Color = Piece.Color.white,
) {
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
            .size(SQUARE_SIZE_UNITS.gridUnitsAsDp())
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
