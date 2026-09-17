package dev.neoneon.flamingo

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val BASE_URL = "https://neoneon.dev/flamingo"

internal class FlamingoApi {
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    suspend fun listGames(playerId: String): Result<List<Game>> = runCatching {
        val response = client.get("$BASE_URL/games?playerID=$playerId")
        if (!response.status.isSuccess()) {
            throw IllegalStateException("HTTP ${response.status.value}: ${response.bodyAsText().take(500)}")
        }
        response.body()
    }

    suspend fun fetchGame(gameId: String): Result<GameDetail> = runCatching {
        val response = client.get("$BASE_URL/games/$gameId")
        if (!response.status.isSuccess()) {
            throw IllegalStateException("HTTP ${response.status.value}: ${response.bodyAsText().take(500)}")
        }
        response.body()
    }

    suspend fun createGame(
        gameId: String,
        lan: String,
        san: String,
        fen: String,
        moveNumber: Int,
        whitePlayerId: String,
        callerPlayerId: String,
    ): Result<GameDetail> = runCatching {
        val response = client.post("$BASE_URL/games") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateGameRequest(
                    gameID = gameId,
                    lan = lan,
                    san = san,
                    fen = fen,
                    moveNumber = moveNumber,
                    whitePlayerID = whitePlayerId,
                    callerPlayerID = callerPlayerId,
                )
            )
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("HTTP ${response.status.value}: ${response.bodyAsText().take(500)}")
        }
        response.body()
    }

    suspend fun recordMove(
        gameId: String,
        lan: String,
        san: String,
        fen: String,
        moveNumber: Int,
        callerPlayerId: String,
        whitePlayerId: String,
    ): Result<RecordMoveResult> = runCatching {
        val response = client.post("$BASE_URL/games/$gameId/moves") {
            contentType(ContentType.Application.Json)
            setBody(
                RecordMoveRequest(
                    lan = lan,
                    san = san,
                    fen = fen,
                    moveNumber = moveNumber,
                    callerPlayerID = callerPlayerId,
                    whitePlayerID = whitePlayerId,
                )
            )
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("HTTP ${response.status.value}: ${response.bodyAsText().take(500)}")
        }
        response.body()
    }

    /**
     * Creates an invite game (one seat open, waiting for an opponent) and mints its share
     * phrase. Exactly one of [whitePlayerId] / [blackPlayerId] is the creator's chosen seat;
     * the other is left null and filled when someone joins by phrase. No first move is sent —
     * the game is created empty and the creator plays their opening move later in [GameView].
     */
    suspend fun createInvite(
        whitePlayerId: String?,
        blackPlayerId: String?,
    ): Result<InviteResponse> = runCatching {
        val response = client.post("$BASE_URL/games/invite") {
            contentType(ContentType.Application.Json)
            setBody(
                InviteRequest(
                    whitePlayerID = whitePlayerId,
                    blackPlayerID = blackPlayerId,
                    origin = LIGHT_PHONE_ORIGIN,
                )
            )
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(inviteErrorMessage(response.status.value))
        }
        response.body()
    }

    /**
     * Re-fetches [gameId]'s share phrase from the server (minting a fresh one there if the
     * previous one expired), so the game view can re-open the share screen without the client
     * ever persisting the phrase. Only valid while the game still has an open seat.
     */
    suspend fun shareInvite(gameId: String): Result<InviteResponse> = runCatching {
        val response = client.post("$BASE_URL/games/$gameId/invite")
        if (!response.status.isSuccess()) {
            throw IllegalStateException(shareErrorMessage(response.status.value))
        }
        response.body()
    }

    /** Joins the invite addressed by [phrase], filling its open seat and activating the game. */
    suspend fun joinByPhrase(
        phrase: String,
        playerId: String,
    ): Result<JoinResponse> = runCatching {
        val response = client.post("$BASE_URL/games/join-by-phrase") {
            contentType(ContentType.Application.Json)
            setBody(JoinRequest(phrase = phrase, playerID = playerId))
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(joinErrorMessage(response.status.value))
        }
        response.body()
    }

    /**
     * Claims this installation's player id and returns the display name the server holds for it.
     *
     * Called once per install (see [PlayerIdentityStore.ensureRegistered]). The server mints a
     * colour name for an id it has never seen; for one it already knows — because the opponent's
     * move created the row first, or because a previous launch already registered — it returns the
     * existing name untouched. Registering twice therefore costs the player nothing.
     */
    suspend fun registerPlayer(playerId: String): Result<PlayerName> = runCatching {
        val response = client.post("$BASE_URL/players") {
            contentType(ContentType.Application.Json)
            setBody(RegisterPlayerRequest(playerID = playerId))
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("HTTP ${response.status.value}: ${response.bodyAsText().take(500)}")
        }
        response.body()
    }

    /**
     * The display names for [playerIds], in one request — what the games list calls so it can
     * title every row without a request per seat.
     *
     * Ids the server has no player for are simply absent from the result; the caller falls back
     * per seat (see [Game.title]) rather than losing the whole batch. An empty input skips the
     * request entirely.
     */
    suspend fun fetchPlayerNames(playerIds: Collection<String>): Result<List<PlayerName>> = runCatching {
        if (playerIds.isEmpty()) return@runCatching emptyList()
        val ids = playerIds.distinct().joinToString(",")
        val response = client.get("$BASE_URL/players?ids=$ids")
        if (!response.status.isSuccess()) {
            throw IllegalStateException("HTTP ${response.status.value}: ${response.bodyAsText().take(500)}")
        }
        response.body()
    }

    /** Renames [playerId] to [name] — the write behind the Account screen. */
    suspend fun renamePlayer(playerId: String, name: String): Result<PlayerName> = runCatching {
        val response = client.patch("$BASE_URL/players/$playerId") {
            contentType(ContentType.Application.Json)
            setBody(RenamePlayerRequest(name = name))
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(renameErrorMessage(response.status.value))
        }
        response.body()
    }

    fun close() {
        client.close()
    }
}

private const val LIGHT_PHONE_ORIGIN = "lightPhone"

/** Short, top-bar-friendly messages for the invite/join error statuses (see docs/flamingo-api.md). */
private fun inviteErrorMessage(status: Int): String = when (status) {
    503 -> "Too many games — try again"
    else -> "Couldn't create game ($status)"
}

private fun shareErrorMessage(status: Int): String = when (status) {
    404 -> "Game not found"
    409 -> "Both players joined"
    503 -> "Try again in a moment"
    else -> "Couldn't get code ($status)"
}

/**
 * 422 is the only one a player can act on: the server rejects a name that is empty, longer than
 * 24 characters, or not a single line. A 404 means this install's id was never registered, which
 * the next launch repairs on its own.
 */
private fun renameErrorMessage(status: Int): String = when (status) {
    404 -> "Not registered yet - try later"
    422 -> "Use 1-24 characters, one line"
    else -> "Couldn't save name ($status)"
}

private fun joinErrorMessage(status: Int): String = when (status) {
    400 -> "Enter a valid 5-letter code"
    404 -> "No game for that code"
    409 -> "Already joined"
    410 -> "Invite expired"
    else -> "Join failed ($status)"
}

@Serializable
private data class CreateGameRequest(
    val gameID: String,
    val lan: String,
    val san: String,
    val fen: String,
    val moveNumber: Int,
    val whitePlayerID: String,
    val callerPlayerID: String,
)

@Serializable
private data class RecordMoveRequest(
    val lan: String,
    val san: String,
    val fen: String,
    val moveNumber: Int,
    val callerPlayerID: String,
    val whitePlayerID: String,
)

// Only the seat the creator chose is non-null; the null seat is left out of the JSON
// (the Json config's encodeDefaults is off, so a field left at its null default is omitted),
// which the backend requires — exactly one seat may be set.
@Serializable
private data class InviteRequest(
    val whitePlayerID: String? = null,
    val blackPlayerID: String? = null,
    val origin: String,
)

@Serializable
private data class RegisterPlayerRequest(
    val playerID: String,
)

@Serializable
private data class RenamePlayerRequest(
    val name: String,
)

@Serializable
private data class JoinRequest(
    val phrase: String,
    val playerID: String,
)
