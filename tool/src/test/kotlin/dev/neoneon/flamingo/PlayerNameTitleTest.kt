package dev.neoneon.flamingo

import kotlin.test.Test
import kotlin.test.assertEquals

/** How a games-list row titles itself once the display names have been looked up. */
class PlayerNameTitleTest {

    // Lowercase as this client mints them, uppercase as the server echoes them back — the same
    // case mismatch `samePlayer` exists for.
    private val whiteLower = "96596872-5fe3-4574-8684-aca1047afa23"
    private val whiteUpper = "96596872-5FE3-4574-8684-ACA1047AFA23"
    private val blackUpper = "AB98F326-AA39-4F83-8E15-EA8ECABBFC05"
    private val stranger = "00000000-1111-2222-3333-444444444444"

    private val names = listOf(
        PlayerName(whiteUpper, "blueberry764"),
        PlayerName(blackUpper, "orange489"),
    ).byPlayerId()

    private fun game(white: String?, black: String?) = Game(
        id = "4f3a2b1c-0000-0000-0000-000000000001",
        fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        whitePlayerID = white,
        blackPlayerID = black,
        status = "active",
        createdAt = "2026-09-17T10:00:00Z",
        updatedAt = "2026-09-17T10:00:00Z",
    )

    /** Our own name is the one name on the row that tells the reader nothing; our color isn't. */
    @Test
    fun titlesOurColorThenTheOpponent() {
        assertEquals(
            "White vs orange489",
            game(whiteUpper, blackUpper).title(whiteUpper, names),
        )
        assertEquals(
            "Black vs blueberry764",
            game(whiteUpper, blackUpper).title(blackUpper, names),
        )
    }

    // The whole reason the lookup map is lower-cased, and the reason the seat is matched with
    // `samePlayer`: the ids on the game, the id in our DataStore, and the ids in the name
    // response can all disagree on case.
    @Test
    fun matchesOurSeatAndTheirNameAcrossCaseMismatch() {
        assertEquals(
            "White vs orange489",
            game(whiteLower, blackUpper).title(whiteUpper, names),
        )
        assertEquals(
            "Black vs blueberry764",
            game(whiteUpper, blackUpper).title(blackUpper.lowercase(), names),
        )
    }

    /** No seat of ours to name — white first, the way a chess game is written. */
    @Test
    fun fallsBackToWhiteFirstWhenWeHoldNeitherSeat() {
        assertEquals(
            "blueberry764 vs orange489",
            game(whiteUpper, blackUpper).title(stranger, names),
        )
        assertEquals(
            "blueberry764 vs orange489",
            game(whiteUpper, blackUpper).title(null, names),
        )
    }

    /**
     * The invite we created as black, before anyone has claimed the white seat. An unclaimed seat
     * and an absent local id are both null, and `samePlayer` is what keeps the two apart: taken
     * for each other, an id-less install would hold the open seat and read "White vs orange489".
     */
    @Test
    fun namesAnUnfilledSeat() {
        assertEquals("Black vs open seat", game(null, blackUpper).title(blackUpper, names))
        assertEquals("open seat vs orange489", game(null, blackUpper).title(stranger, names))
        assertEquals("open seat vs orange489", game(null, blackUpper).title(null, names))
    }

    // A failed or not-yet-arrived lookup must still produce a title of the usual shape rather
    // than a blank row — our own seat needs no lookup at all, so only the opponent can degrade.
    @Test
    fun fallsBackToTheShortIdWhenTheOpponentNameIsMissing() {
        assertEquals(
            "White vs AB98F326",
            game(whiteUpper, blackUpper).title(whiteUpper, emptyMap()),
        )
        assertEquals(
            "96596872 vs AB98F326",
            game(whiteLower, blackUpper).title(stranger, emptyMap()),
        )
    }
}
