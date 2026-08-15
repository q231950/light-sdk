package dev.neoneon.flamingo

import kotlin.test.Test
import kotlin.test.assertEquals

class GameActionsTest {

    // The two seats, in the shapes they really arrive in: the server echoes ids UPPERCASE while
    // this client mints and stores them lowercase (see samePlayer).
    private companion object {
        const val US_STORED = "96596872-5fe3-4574-8684-aca1047afa23"
        const val US_ECHOED = "96596872-5FE3-4574-8684-ACA1047AFA23"
        const val THEM = "AB98F326-AA39-4F83-8E15-EA8ECABBFC05"
    }

    private var nextTimestamp = 0

    // Entries default to a plain move; each test names only the fields it cares about.
    // Timestamps auto-increment so a log written in call order is already in wire order.
    private fun entry(
        moveNumber: Int,
        lan: String? = "e2e4",
        drawOfferPlayerID: String? = null,
        drawAcceptPlayerID: String? = null,
        drawDeclinePlayerID: String? = null,
        resignPlayerID: String? = null,
    ) = Move(
        moveNumber = moveNumber,
        lan = lan,
        fenAfter = "f",
        timestamp = "2026-07-23T16:0%d:00Z".format(nextTimestamp++),
        id = "m$moveNumber-${nextTimestamp}",
        drawOfferPlayerID = drawOfferPlayerID,
        drawAcceptPlayerID = drawAcceptPlayerID,
        drawDeclinePlayerID = drawDeclinePlayerID,
        resignPlayerID = resignPlayerID,
    )

    private fun offer(moveNumber: Int, by: String) =
        entry(moveNumber, lan = null, drawOfferPlayerID = by)

    @Test
    fun emptyLogIsInPlayWithNoOffer() {
        assertEquals(GameActionState(), gameActionState(emptyList(), US_STORED))
    }

    @Test
    fun plainMoveLogIsInPlayWithNoOffer() {
        val moves = listOf(entry(1), entry(2), entry(3))

        assertEquals(GameActionState(), gameActionState(moves, US_STORED))
    }

    @Test
    fun opponentsOfferAsTheLastEntryIsIncoming() {
        val moves = listOf(entry(1), offer(2, by = THEM))

        assertEquals(DrawOffer.Incoming, gameActionState(moves, US_STORED).drawOffer)
    }

    @Test
    fun ourOwnOfferAsTheLastEntryIsOutgoing() {
        // The id comes back uppercase from the server while ours is stored lowercase — a plain
        // `==` here would read our own offer as the opponent's and demand we answer it.
        val moves = listOf(entry(1), offer(2, by = US_ECHOED))

        assertEquals(DrawOffer.Outgoing, gameActionState(moves, US_STORED).drawOffer)
    }

    @Test
    fun offerFollowedByAMoveIsNoLongerPending() {
        // The offer and the move that lapses it share a move number (the draw entry is numbered
        // with the half-move its actor would have played), so only the timestamp orders them.
        val moves = listOf(entry(1), offer(2, by = THEM), entry(2, lan = "e7e5"))

        assertEquals(GameActionState(), gameActionState(moves, US_STORED))
    }

    @Test
    fun acceptedOfferIsAnAgreedDrawWhoeverAccepted() {
        val theyAccepted = listOf(
            entry(1),
            offer(2, by = US_ECHOED),
            entry(3, lan = null, drawAcceptPlayerID = THEM),
        )
        assertEquals(
            // Accepting settles the offer that produced it, so nothing is left to answer.
            GameActionState(drawOffer = DrawOffer.None, agreedDraw = true),
            gameActionState(theyAccepted, US_STORED),
        )

        val weAccepted = listOf(
            entry(1),
            offer(2, by = THEM),
            entry(3, lan = null, drawAcceptPlayerID = US_ECHOED),
        )
        assertEquals(
            GameActionState(drawOffer = DrawOffer.None, agreedDraw = true),
            gameActionState(weAccepted, US_STORED),
        )
    }

    @Test
    fun anAgreementAnywhereInTheLogOutranksALaterOffer() {
        // A game can't be un-drawn by whatever the server happens to have recorded afterwards,
        // and the stale offer must not still be demanding a decision.
        val moves = listOf(
            entry(1),
            entry(2, lan = null, drawAcceptPlayerID = THEM),
            offer(3, by = THEM),
        )

        assertEquals(
            GameActionState(drawOffer = DrawOffer.None, agreedDraw = true),
            gameActionState(moves, US_STORED),
        )
    }

    @Test
    fun theirDeclineAsTheLastEntryReportsDeclined() {
        val moves = listOf(
            entry(1),
            offer(2, by = US_ECHOED),
            entry(2, lan = null, drawDeclinePlayerID = THEM),
        )

        assertEquals(DrawOffer.Declined, gameActionState(moves, US_STORED).drawOffer)
    }

    @Test
    fun ourOwnDeclineReportsNothing() {
        // We already know we said no; there's nothing to tell us about.
        val moves = listOf(
            entry(1),
            offer(2, by = THEM),
            entry(2, lan = null, drawDeclinePlayerID = US_ECHOED),
        )

        assertEquals(GameActionState(), gameActionState(moves, US_STORED))
    }

    @Test
    fun halfMoveNumberCountsPliesFromTheOpening() {
        // White's first move is 1, black's reply 2, white's second 3 — the numbering the iOS
        // companion and the backend both speak.
        assertEquals(1, halfMoveNumber("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"))
        assertEquals(2, halfMoveNumber("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"))
        assertEquals(3, halfMoveNumber("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2"))
    }

    @Test
    fun halfMoveNumberGivesAnActionTheSlotOfTheMoveItPrecedes() {
        // A draw or resign frame carries the *current* position, so it numbers itself as the move
        // about to be played — deliberately sharing that slot. The backend keeps one row per
        // played ply and lets actions sit alongside, so nothing is displaced.
        val afterQh5 = "rnbqkbnr/pppp1ppp/8/4p2Q/4P3/8/PPPP1PPP/RNB1KBNR b KQkq - 1 2"

        assertEquals(4, halfMoveNumber(afterQh5))
        // …and black's reply, played from that same position, claims the same number.
        assertEquals(halfMoveNumber(afterQh5), 4)
    }

    @Test
    fun halfMoveNumberFallsBackToOneOnAnUnreadableFen() {
        // Matches the companion's fallback rather than throwing: a malformed FEN should not take
        // the board down mid-game.
        assertEquals(1, halfMoveNumber(""))
        assertEquals(1, halfMoveNumber("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -"))
        assertEquals(1, halfMoveNumber("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 x"))
    }
}
