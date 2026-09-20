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

    private fun game(white: String?, black: String?) = Game(
        id = "4f3a2b1c-0000-0000-0000-000000000001",
        fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        whitePlayerID = white,
        blackPlayerID = black,
        status = "active",
        createdAt = "2026-09-17T10:00:00Z",
        updatedAt = "2026-09-17T10:00:00Z",
    )

    @Test
    fun titlesWhiteFirst() {
        val names = listOf(
            PlayerName(whiteUpper, "blueberry764"),
            PlayerName(blackUpper, "orange489"),
        ).byPlayerId()

        assertEquals("blueberry764 vs orange489", game(whiteUpper, blackUpper).title(names))
    }

    // The whole reason the lookup map is lower-cased: the seat ids on the game and the ids in
    // the name response can disagree on case, since one may have come from our own DataStore.
    @Test
    fun matchesNamesAcrossCaseMismatch() {
        val names = listOf(
            PlayerName(whiteUpper, "blueberry764"),
            PlayerName(blackUpper, "orange489"),
        ).byPlayerId()

        assertEquals("blueberry764 vs orange489", game(whiteLower, blackUpper).title(names))
    }

    @Test
    fun namesAnUnfilledSeat() {
        val names = listOf(PlayerName(blackUpper, "orange489")).byPlayerId()

        assertEquals("open seat vs orange489", game(null, blackUpper).title(names))
    }

    // A failed or not-yet-arrived lookup must still produce a title of the usual shape, per
    // seat, rather than a blank row.
    @Test
    fun fallsBackPerSeatWhenANameIsMissing() {
        val names = listOf(PlayerName(whiteUpper, "blueberry764")).byPlayerId()

        assertEquals("blueberry764 vs AB98F326", game(whiteUpper, blackUpper).title(names))
        assertEquals(
            "96596872 vs AB98F326",
            game(whiteLower, blackUpper).title(emptyMap()),
        )
    }
}
