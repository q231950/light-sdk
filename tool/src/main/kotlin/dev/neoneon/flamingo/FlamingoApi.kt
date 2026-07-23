package dev.neoneon.flamingo

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
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

private fun joinErrorMessage(status: Int): String = when (status) {
    400 -> "Not two chess moves"
    404 -> "No game for that phrase"
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
private data class JoinRequest(
    val phrase: String,
    val playerID: String,
)
