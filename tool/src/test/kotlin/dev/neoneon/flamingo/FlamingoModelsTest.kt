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
        val body = """{"gameID":"2A787C32","phrase":"rook b2 b7, knight g1 f3","expiresAt":"2026-07-30T14:13:39Z"}"""

        val response = json.decodeFromString<InviteResponse>(body)

        assertEquals("2A787C32", response.gameID)
        assertEquals("rook b2 b7, knight g1 f3", response.phrase)
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
}
