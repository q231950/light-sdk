package dev.neoneon.flamingo

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.designVerticalPxToDp
import dev.neoneon.chesskit.Board
import dev.neoneon.chesskit.Piece
import dev.neoneon.chesskit.Position
import dev.neoneon.chesskit.Square

// Same weight as the SDK's active code-input underline, so the check marker reads as a line
// rather than a hairline (see LightCodeInput's ACTIVE_UNDERLINE_THICKNESS_PX).
private const val CHECK_UNDERLINE_THICKNESS_PX = 7f
private const val CHECK_UNDERLINE_DURATION_MS = 220

/**
 * The square holding the king that [state] reports as being in check, or `null` if neither is.
 *
 * Checkmate counts too: the king is in check there as well, and it's the moment most worth
 * marking.
 *
 * This reads [Board.state] rather than deriving check from [position] alone because chesskit keeps
 * its attack generation private. Note [Board] only evaluates the right king when its state was
 * updated *by a move* (see `Board.updateState`), so a board advanced with `move` /
 * `completePromotion` — including one rebuilt by replaying a game's moves — reports check
 * correctly, while one whose position was set via `Board.update` reads as `active` even from a
 * position that really is check.
 */
internal fun checkedKingSquare(state: Board.State, position: Position): Square? {
    val color = when (state) {
        is Board.State.check -> state.color
        is Board.State.checkmate -> state.color
        else -> null
    } ?: return null

    return position.pieces.firstOrNull { it.kind == Piece.Kind.king && it.color == color }?.square
}

/**
 * Interactive chess board rendering a chesskit [Position] with tap-to-select/tap-to-move squares.
 *
 * [orientation] is the local player's color: their pieces sit at the bottom, so a black
 * player sees the board flipped (rank 1 on top, files h→a left-to-right).
 *
 * [boardSize] is the full side length of the (square) board; each square is [boardSize] / 8. The
 * caller sizes it to fit the space actually available (see GameView), so it never overflows the
 * screen regardless of device proportions.
 *
 * [checkedKingSquare] marks the king that's in check with a line along the bottom edge of that one
 * square, drawing in from its left edge to its right. Callers derive it from their board with the
 * `checkedKingSquare` function above.
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
    checkedKingSquare: Square? = null,
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
                        isInCheck = square == checkedKingSquare,
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
    isInCheck: Boolean,
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

    // The check line draws in from this square's left edge to its right edge — screen-left to
    // screen-right, so it looks the same however the board is oriented. At 0f there's nothing to
    // draw, which is every square but the checked king's.
    val checkProgress by animateFloatAsState(
        targetValue = if (isInCheck) 1f else 0f,
        animationSpec = tween(durationMillis = CHECK_UNDERLINE_DURATION_MS, easing = LinearEasing),
        label = "checkUnderline",
    )
    val checkSemantics = if (isInCheck) {
        Modifier.semantics { contentDescription = "King in check" }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .size(squareSize)
            .background(content.copy(alpha = backgroundAlpha))
            .clickable(onClick = onTap)
            .then(checkSemantics),
        contentAlignment = Alignment.Center,
    ) {
        if (piece != null) {
            LightText(
                text = piece.copy(color = piece.color.opposite).graphic,
                variant = LightTextVariant.Heading,
                align = TextAlign.Center,
            )
        }
        if (checkProgress > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(checkProgress)
                    .height(CHECK_UNDERLINE_THICKNESS_PX.designVerticalPxToDp())
                    .background(content),
            )
        }
    }
}
