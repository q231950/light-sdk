package dev.neoneon.flamingo

import kotlinx.serialization.Serializable

@Serializable
data class Game(
    val id: String,
    val fen: String,
    // Nullable: a phrase invite created by a black initiator leaves the white seat open
    // (server returns null) until a joiner fills it.
    val whitePlayerID: String? = null,
    val blackPlayerID: String? = null,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
)

/** Statuses the backend uses for a game that's still in progress; anything else is over. */
private val activeGameStatuses = setOf("active", "waitingForOpponent")

val Game.isActive: Boolean get() = status in activeGameStatuses

/**
 * The raw backend status rendered for humans — `waitingForOpponent` is no way to talk to
 * someone. `active` only means "in progress", so it's resolved against [playerId] into whose
 * move it actually is. The server declares checkmate/stalemate but never writes them today;
 * they're handled anyway, and anything unrecognized falls back to the split camelCase word.
 */
fun Game.statusLabel(playerId: String?): String = when (status) {
    "waitingForOpponent" -> "waiting for opponent"
    "active" -> when (isMyTurn(playerId)) {
        true -> "your turn"
        false -> "their turn"
        null -> "in progress"
    }
    // Neither the winner of a checkmate nor the resigning player is knowable from a list
    // entry alone (the resigner lives on the move log), so these stay neutral.
    "checkmate" -> "checkmate"
    "stalemate" -> "stalemate"
    "draw" -> "draw"
    "resigned" -> "resigned"
    else -> status.humanized()
}

/**
 * Whether it's this player's move, or null when that can't be told.
 *
 * `Game.fen` is the position the last move was played *from*, not the one it produced:
 * both clients send the pre-move FEN (see GameView.submitMove) into the server's
 * `fen_after` field, and the recorder copies it onto the game — the iOS companion reads
 * it back the same way, deriving the mover from this field. So the side to move named in
 * the FEN is whoever just moved, and the move belongs to the *other* color.
 *
 * Reads the FEN's fields directly rather than building a Board per list row. Null when
 * there's no local identity, we hold neither seat, or the FEN carries no side-to-move.
 */
private fun Game.isMyTurn(playerId: String?): Boolean? {
    val iAmWhite = when {
        samePlayer(playerId, whitePlayerID) -> true
        samePlayer(playerId, blackPlayerID) -> false
        else -> return null
    }
    val fields = fen.split(' ')
    val whiteJustMoved = when (fields.getOrNull(1)) {
        "w" -> true
        "b" -> false
        else -> return null
    }
    // The opening position is the one case the pre-move FEN can't resolve: it looks
    // identical before white's first move and right after it (that move was played
    // *from* the opening), and the list payload carries no move count to break the tie.
    if (whiteJustMoved && fields.getOrNull(5) == "1") return null
    return iAmWhite != whiteJustMoved
}

private val camelCaseBoundary = Regex("(?<=[a-z0-9])(?=[A-Z])")

/** `timedOut` -> `timed out`, so an unrecognized status still reads as words. */
private fun String.humanized(): String =
    split(camelCaseBoundary).joinToString(" ") { it.lowercase() }

@Serializable
data class Move(
    val moveNumber: Int,
    // Nullable: draw-offer / draw-accept / draw-decline / resign log entries carry no
    // chess move, so the server sends `lan: null` for them (MoveDTO.lan is optional).
    // A non-null type here fails to deserialize any game that contains such an action.
    val lan: String? = null,
    val fenAfter: String,
    val timestamp: String,
    val id: String,
    // The four game actions the backend records as move-log entries rather than on the game
    // itself. At most one is set on any entry, and an entry that sets one carries no [lan] —
    // the actor's id is the whole payload. Optional so bodies written before this build (and
    // the plain moves that make up most of a log) still decode.
    val drawOfferPlayerID: String? = null,
    val drawAcceptPlayerID: String? = null,
    val drawDeclinePlayerID: String? = null,
    val resignPlayerID: String? = null,
)

@Serializable
data class GameDetail(
    val game: Game,
    val moves: List<Move>,
)

@Serializable
data class RecordMoveResult(
    val move: Move,
    val game: Game,
)

/** Response to `POST /flamingo/games/invite` — the freshly created waiting game plus its share phrase. */
@Serializable
data class InviteResponse(
    val gameID: String,
    val phrase: String,
    val expiresAt: String,
)

/** Response to `POST /flamingo/games/join-by-phrase` — the game after our seat was filled. */
@Serializable
data class JoinResponse(
    val game: Game,
)
