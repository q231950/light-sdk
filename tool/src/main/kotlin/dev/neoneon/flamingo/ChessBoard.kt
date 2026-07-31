package dev.neoneon.flamingo

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
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

// The last-move arrow, in fractions of a square rather than design pixels: unlike the check
// underline it spans two squares, and its length already varies with the move, so a fixed-pixel
// arrow would look spindly on a long move and stubby on a short one.
private const val ARROW_END_INSET = 0.28f    // clears the piece glyph at either end
private const val ARROW_SHAFT_WIDTH = 0.06f
private const val ARROW_HEAD_LENGTH = 0.26f
private const val ARROW_HEAD_HALF_WIDTH = 0.11f

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
 *
 * [lastMove] draws a small arrow from a move's origin square to its destination. Used to point out
 * the move a finished game ended on; during play the board is left unmarked.
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
    lastMove: Pair<Square, Square>? = null,
) {
    val squareSize = boardSize / 8
    val rankOrder = boardRankOrder(orientation)
    val fileOrder = boardFileOrder(orientation)
    // The arrow spans two squares, so unlike the check underline it can't be drawn by a square:
    // it goes over the whole board, in the same box.
    Box(modifier = modifier.size(boardSize)) {
        Column {
            for (rankValue in rankOrder) {
                Row {
                    for (file in fileOrder) {
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
        if (lastMove != null) {
            LastMoveArrow(
                from = lastMove.first,
                to = lastMove.second,
                fileOrder = fileOrder,
                rankOrder = rankOrder,
            )
        }
    }
}

/** The left-to-right file order the board lays out in, seen by a player of [orientation]. */
internal fun boardFileOrder(orientation: Piece.Color): List<Square.File> =
    if (orientation == Piece.Color.black) Square.File.entries.reversed() else Square.File.entries

/** The top-to-bottom rank order the board lays out in: your own back rank is nearest you. */
internal fun boardRankOrder(orientation: Piece.Color): List<Int> =
    (if (orientation == Piece.Color.black) (1..8) else (8 downTo 1)).toList()

/**
 * The center of [square] in board pixels, given the board's own layout order and its [squareSize].
 *
 * Derived from the same lists the squares are laid out from, so the arrow can't disagree with the
 * board about which corner is which when it's flipped for black.
 */
internal fun squareCenter(
    square: Square,
    fileOrder: List<Square.File>,
    rankOrder: List<Int>,
    squareSize: Float,
): Offset = Offset(
    x = (fileOrder.indexOf(square.file) + 0.5f) * squareSize,
    y = (rankOrder.indexOf(square.rank.value) + 0.5f) * squareSize,
)

/** A thin arrow from [from]'s center to [to]'s, drawn over the whole board. */
@Composable
private fun LastMoveArrow(
    from: Square,
    to: Square,
    fileOrder: List<Square.File>,
    rankOrder: List<Int>,
) {
    val color = LightThemeTokens.colors.content
    val description = "Last move ${from.notation} to ${to.notation}"

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = description },
    ) {
        val square = size.width / 8f
        val start = squareCenter(from, fileOrder, rankOrder, square)
        val end = squareCenter(to, fileOrder, rankOrder, square)
        val length = (end - start).getDistance()
        // A null move can't reach here (chesskit never produces one), but a zero-length vector
        // would divide by zero below.
        if (length == 0f) return@Canvas

        val direction = Offset((end.x - start.x) / length, (end.y - start.y) / length)
        val tip = end - direction * (ARROW_END_INSET * square)
        val tail = start + direction * (ARROW_END_INSET * square)
        // Nothing left to draw once the inset eats the whole arrow — impossible for a legal move
        // (the shortest is one square, and the head fits), but cheap to be sure of.
        if ((tip - tail).getDistance() <= ARROW_HEAD_LENGTH * square) return@Canvas

        val headBase = tip - direction * (ARROW_HEAD_LENGTH * square)
        val across = Offset(-direction.y, direction.x) * (ARROW_HEAD_HALF_WIDTH * square)

        drawLine(
            color = color,
            start = tail,
            end = headBase,
            strokeWidth = ARROW_SHAFT_WIDTH * square,
            cap = StrokeCap.Round,
        )
        drawPath(
            path = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(headBase.x + across.x, headBase.y + across.y)
                lineTo(headBase.x - across.x, headBase.y - across.y)
                close()
            },
            color = color,
        )
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
