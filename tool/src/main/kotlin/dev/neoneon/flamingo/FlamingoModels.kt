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
