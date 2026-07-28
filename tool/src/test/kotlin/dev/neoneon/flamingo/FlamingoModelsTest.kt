package dev.neoneon.flamingo

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlamingoModelsTest {

    // Same config as FlamingoApi's client: unknown keys (e.g. the newer `gameOrigin`) are ignored.
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesWaitingGameWithAbsentWhitePlayerAndUnknownGameOrigin() {
        // Real GET /flamingo/games/{id} body for a black-initiator waiting game: `whitePlayerID`
        // is omitted entirely (Swift encodes nil as absent), `gameOrigin` is an unknown key,
        // and there are no moves yet. Must decode with whitePlayerID == null.
        val body = """{"game":{"createdAt":"2026-07-23T16:01:23Z","status":"waitingForOpponent",""" +
            """"gameOrigin":"lightPhone","id":"2A787C32","blackPlayerID":"AB98F326",""" +
            """"fen":"rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",""" +
            """"updatedAt":"2026-07-23T16:02:01Z"},"moves":[]}"""

        val detail = json.decodeFromString<GameDetail>(body)

        assertNull(detail.game.whitePlayerID)
        assertEquals("AB98F326", detail.game.blackPlayerID)
        assertEquals("waitingForOpponent", detail.game.status)
        assertTrue(detail.moves.isEmpty())
    }

    @Test
    fun decodesActiveGameWithBothSeats() {
        val body = """{"game":{"createdAt":"x","status":"active","gameOrigin":"lightPhone",""" +
            """"id":"2A787C32","whitePlayerID":"96596872","blackPlayerID":"AB98F326",""" +
            """"fen":"f","updatedAt":"y"},"moves":[]}"""

        val detail = json.decodeFromString<GameDetail>(body)

        assertEquals("96596872", detail.game.whitePlayerID)
        assertEquals("AB98F326", detail.game.blackPlayerID)
        assertEquals("active", detail.game.status)
    }

    @Test
    fun decodesGameWithNullLanMove() {
        // Draw-offer / resign log entries carry `lan: null`; a non-null Move.lan would make the
        // whole game fail to deserialize.
        val body = """{"game":{"id":"g","fen":"f","status":"resigned","createdAt":"x","updatedAt":"y",""" +
            """"whitePlayerID":"W","blackPlayerID":"B"},"moves":[""" +
            """{"moveNumber":1,"lan":"e2e4","fenAfter":"f1","timestamp":"t","id":"m1"},""" +
            """{"moveNumber":2,"lan":null,"fenAfter":"f1","timestamp":"t","id":"m2"}]}"""

        val detail = json.decodeFromString<GameDetail>(body)

        assertEquals(2, detail.moves.size)
        assertEquals("e2e4", detail.moves[0].lan)
        assertNull(detail.moves[1].lan)
    }

    @Test
    fun decodesInviteResponse() {
        val body = """{"gameID":"2A787C32","phrase":"ADKRF","expiresAt":"2026-07-30T14:13:39Z"}"""

        val response = json.decodeFromString<InviteResponse>(body)

        assertEquals("2A787C32", response.gameID)
        assertEquals("ADKRF", response.phrase)
        assertEquals("2026-07-30T14:13:39Z", response.expiresAt)
    }

    @Test
    fun decodesJoinResponseThenResolvesJoinerAsWhite() {
        // After join the server fills the white seat with the joiner and echoes it UPPERCASE.
        val body = """{"game":{"id":"2A787C32","fen":"f","status":"active","createdAt":"x","updatedAt":"y",""" +
            """"whitePlayerID":"96596872-5FE3-4574-8684-ACA1047AFA23",""" +
            """"blackPlayerID":"AB98F326-AA39-4F83-8E15-EA8ECABBFC05"}}"""

        val response = json.decodeFromString<JoinResponse>(body)
        val myLowercaseId = "96596872-5fe3-4574-8684-aca1047afa23"

        assertEquals("active", response.game.status)
        // The color predicate used by JoinByPhraseScreen / GameView must resolve white, not black.
        assertTrue(samePlayer(response.game.whitePlayerID, myLowercaseId))
    }

    private fun game(
        status: String,
        fen: String = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        whitePlayerID: String? = "W",
        blackPlayerID: String? = "B",
    ) = Game(
        id = "g",
        fen = fen,
        whitePlayerID = whitePlayerID,
        blackPlayerID = blackPlayerID,
        status = status,
        createdAt = "x",
        updatedAt = "y",
    )

    @Test
    fun labelsWaitingGameInWords() {
        assertEquals("waiting for opponent", game("waitingForOpponent").statusLabel("W"))
    }

    @Test
    fun labelsActiveGameByWhoseTurnItIs() {
        val whiteToMove = game("active")
        assertEquals("your turn", whiteToMove.statusLabel("W"))
        assertEquals("their turn", whiteToMove.statusLabel("B"))

        val blackToMove = game("active", fen = "rnbqkbnr/8/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1")
        assertEquals("your turn", blackToMove.statusLabel("B"))
        assertEquals("their turn", blackToMove.statusLabel("W"))
    }

    @Test
    fun matchesSeatsCaseInsensitivelyForTurn() {
        // The server echoes ids UPPERCASE; the locally stored id is lowercase.
        val active = game("active", whitePlayerID = "96596872-5FE3-4574-8684-ACA1047AFA23")
        assertEquals("your turn", active.statusLabel("96596872-5fe3-4574-8684-aca1047afa23"))
    }

    @Test
    fun fallsBackToNeutralActiveLabelWhenTurnIsUnknowable() {
        // No local identity, not seated in the game, and a FEN with no side-to-move field.
        assertEquals("in progress", game("active").statusLabel(null))
        assertEquals("in progress", game("active").statusLabel("someone-else"))
        assertEquals("in progress", game("active", fen = "f").statusLabel("W"))
    }

    @Test
    fun labelsFinishedGames() {
        assertEquals("checkmate", game("checkmate").statusLabel("W"))
        assertEquals("stalemate", game("stalemate").statusLabel("W"))
        assertEquals("draw", game("draw").statusLabel("W"))
        assertEquals("resigned", game("resigned").statusLabel("W"))
    }

    @Test
    fun humanizesUnknownStatus() {
        // A status this build doesn't know about must still read as words, not camelCase.
        assertEquals("timed out", game("timedOut").statusLabel("W"))
        assertEquals("abandoned", game("abandoned").statusLabel("W"))
    }
}
