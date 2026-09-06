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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
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
import kotlin.math.atan2

// Same weight as the SDK's active code-input underline, so the check marker reads as a line
// rather than a hairline (see LightCodeInput's ACTIVE_UNDERLINE_THICKNESS_PX).
private const val CHECK_UNDERLINE_THICKNESS_PX = 7f
private const val CHECK_UNDERLINE_DURATION_MS = 220

// The last-move markers, in fractions of a square rather than design pixels, so they scale with
// however big the board ends up on screen instead of looking spindly or oversized.
private const val LAST_MOVE_DOT_RADIUS_FRACTION = 0.06f
// How far from the to-square's own center the little arrow sits — offset toward whichever
// corner/edge the move arrived from, so it clears the piece glyph parked in the center. Pushed
// further out and drawn smaller than an early pass: at the piece glyph's size, anything nearer or
// bigger kept clipping it.
private const val LAST_MOVE_MARKER_INSET_FRACTION = 0.34f
private const val LAST_MOVE_ARROW_LENGTH_FRACTION = 0.12f
private const val LAST_MOVE_ARROW_HALF_WIDTH_FRACTION = 0.05f

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
 * [lastMove] marks a move's origin square with a small dot and its destination square with a small
 * arrow, oriented toward whichever corner or edge the move arrived from. Always shown for the move
 * a finished game ended on (or, mid-replay, whichever move produced the frame on screen); during
 * live play it's the caller's choice whether to pass one.
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
    Box(modifier = modifier.size(boardSize)) {
        Column {
            for (rankValue in rankOrder) {
                Row {
                    for (file in fileOrder) {
                        val square = Square(file, Square.Rank(rankValue))
                        val lastMoveOctant = lastMove
                            ?.takeIf { it.second == square }
                            ?.let { (from, to) -> octantOf(lastMoveDirection(from, to, fileOrder, rankOrder)) }
                        ChessSquare(
                            piece = position.piece(at = square),
                            isDark = square.color == Square.Color.dark,
                            isSelected = square == selectedSquare,
                            isLegalTarget = square in legalTargets,
                            isInCheck = square == checkedKingSquare,
                            isLastMoveOrigin = lastMove?.first == square,
                            lastMoveOctant = lastMoveOctant,
                            lastMoveDescription = lastMove
                                ?.takeIf { it.second == square }
                                ?.let { "Last move ${it.first.notation} to ${it.second.notation}" },
                            squareSize = squareSize,
                            onTap = { onSquareTap(square) },
                        )
                    }
                }
            }
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

/**
 * The 8 compass directions a last-move marker can sit in on a square, screen-space (N = up the
 * screen, independent of board orientation — [lastMoveDirection] already flips for black).
 */
internal enum class LastMoveOctant(val unit: Offset) {
    N(Offset(0f, -1f)),
    NE(Offset(0.7071f, -0.7071f)),
    E(Offset(1f, 0f)),
    SE(Offset(0.7071f, 0.7071f)),
    S(Offset(0f, 1f)),
    SW(Offset(-0.7071f, 0.7071f)),
    W(Offset(-1f, 0f)),
    NW(Offset(-0.7071f, -0.7071f)),
}

/**
 * The screen-space step from [from] to [to], in whole squares rather than pixels: orientation-aware
 * via [fileOrder]/[rankOrder] — the same index arithmetic [squareCenter] uses — but exact, since it
 * never multiplies by a pixel size that would need rounding back out.
 */
internal fun lastMoveDirection(
    from: Square,
    to: Square,
    fileOrder: List<Square.File>,
    rankOrder: List<Int>,
): Offset = Offset(
    x = (fileOrder.indexOf(to.file) - fileOrder.indexOf(from.file)).toFloat(),
    y = (rankOrder.indexOf(to.rank.value) - rankOrder.indexOf(from.rank.value)).toFloat(),
)

/**
 * Buckets [direction] into the nearest of the 8 [LastMoveOctant]s. Every rook/bishop/queen/king/pawn
 * move's direction already sits exactly on one of the 8 (a pure file, a pure rank, or a pure
 * diagonal), so those land without rounding. A knight's isn't: its direction sits at ~26.57° or
 * ~63.43° from the nearer axis, which is always closer to the diagonal bucket than the axis one — so
 * every knight move lands on a corner, never an edge midpoint. Null only for a zero vector, which no
 * legal move produces (a piece always leaves the square it started on).
 */
internal fun octantOf(direction: Offset): LastMoveOctant? {
    if (direction == Offset.Zero) return null
    val degrees = Math.toDegrees(atan2(-direction.y, direction.x).toDouble())
    // atan2 runs counter-clockwise from east (0°); floorMod handles the wrap at ±180°.
    val index = Math.floorMod(Math.round(degrees / 45.0).toInt(), 8)
    return listOf(
        LastMoveOctant.E, LastMoveOctant.NE, LastMoveOctant.N, LastMoveOctant.NW,
        LastMoveOctant.W, LastMoveOctant.SW, LastMoveOctant.S, LastMoveOctant.SE,
    )[index]
}

@Composable
private fun ChessSquare(
    piece: Piece?,
    isDark: Boolean,
    isSelected: Boolean,
    isLegalTarget: Boolean,
    isInCheck: Boolean,
    isLastMoveOrigin: Boolean,
    lastMoveOctant: LastMoveOctant?,
    lastMoveDescription: String?,
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
    // A square is never both the checked king's and a last move's endpoint at once — a king can't
    // move into check on itself — so there's never a second description competing for this slot.
    val markerDescription = if (isInCheck) "King in check" else lastMoveDescription
    val markerSemantics = markerDescription?.let { description ->
        Modifier.semantics { contentDescription = description }
    } ?: Modifier

    Box(
        modifier = Modifier
            .size(squareSize)
            .background(content.copy(alpha = backgroundAlpha))
            .clickable(onClick = onTap)
            .then(markerSemantics),
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
        if (isLastMoveOrigin || lastMoveOctant != null) {
            Canvas(modifier = Modifier.size(squareSize)) {
                val square = size.width
                if (isLastMoveOrigin) {
                    drawCircle(
                        color = content,
                        radius = square * LAST_MOVE_DOT_RADIUS_FRACTION,
                        center = center,
                    )
                }
                if (lastMoveOctant != null) {
                    val unit = lastMoveOctant.unit
                    // Anchored against unit (the direction of travel), not with it: the marker
                    // sits on the near side of the square — the edge/corner closest to the square
                    // the piece came from — rather than the far side it moved toward.
                    val anchor = center - unit * (square * LAST_MOVE_MARKER_INSET_FRACTION)
                    val tip = anchor + unit * (square * LAST_MOVE_ARROW_LENGTH_FRACTION / 2f)
                    val base = anchor - unit * (square * LAST_MOVE_ARROW_LENGTH_FRACTION / 2f)
                    val across = Offset(-unit.y, unit.x) * (square * LAST_MOVE_ARROW_HALF_WIDTH_FRACTION)
                    drawPath(
                        path = Path().apply {
                            moveTo(tip.x, tip.y)
                            lineTo(base.x + across.x, base.y + across.y)
                            lineTo(base.x - across.x, base.y - across.y)
                            close()
                        },
                        color = content,
                    )
                }
            }
        }
    }
}
