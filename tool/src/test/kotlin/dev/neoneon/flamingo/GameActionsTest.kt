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
            entry(2, lan = null, drawAcceptPlayerID = THEM),
        )
        assertEquals(GameOutcome.DrawAgreed, gameActionState(theyAccepted, US_STORED).outcome)

        val weAccepted = listOf(
            entry(1),
            offer(2, by = THEM),
            entry(2, lan = null, drawAcceptPlayerID = US_ECHOED),
        )
        assertEquals(GameOutcome.DrawAgreed, gameActionState(weAccepted, US_STORED).outcome)
        // An agreed draw leaves nothing outstanding to answer.
        assertEquals(DrawOffer.None, gameActionState(weAccepted, US_STORED).drawOffer)
    }

    @Test
    fun resignByUsIsWeResigned() {
        val moves = listOf(entry(1), entry(2, lan = null, resignPlayerID = US_ECHOED))

        assertEquals(GameOutcome.WeResigned, gameActionState(moves, US_STORED).outcome)
    }

    @Test
    fun resignByThemIsTheyResigned() {
        val moves = listOf(entry(1), entry(2, lan = null, resignPlayerID = THEM))

        assertEquals(GameOutcome.TheyResigned, gameActionState(moves, US_STORED).outcome)
    }

    @Test
    fun resignWinsOverAPendingOffer() {
        // Offering and then resigning rather than waiting for an answer: the game is over, and
        // the unanswered offer must not still be demanding a decision.
        val moves = listOf(
            entry(1),
            offer(2, by = THEM),
            entry(2, lan = null, resignPlayerID = THEM),
        )

        assertEquals(
            GameActionState(drawOffer = DrawOffer.None, outcome = GameOutcome.TheyResigned),
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
    fun lastLogEntryNumberCountsDrawAndResignEntries() {
        // The one that matters: an offer at 4 must push the reply to 5. Numbering the reply 4
        // would have the recorder treat it as a replay of the offer and write nothing.
        val moves = listOf(entry(1), entry(2), entry(3), offer(4, by = THEM))

        assertEquals(4, lastLogEntryNumber(moves))
    }

    @Test
    fun lastLogEntryNumberTakesTheHighestWhenAPeerReusedANumber() {
        // The iOS companion derives its numbers from the FEN, so it can write an offer sharing
        // a number with the move beside it. We still have to move past the highest one used.
        val moves = listOf(entry(1), offer(2, by = THEM), entry(2, lan = "e7e5"))

        assertEquals(2, lastLogEntryNumber(moves))
    }

    @Test
    fun lastLogEntryNumberOfAnEmptyLogIsZero() {
        assertEquals(0, lastLogEntryNumber(emptyList()))
        // A game whose only entry is a draw offer has still used number 1.
        assertEquals(1, lastLogEntryNumber(listOf(offer(1, by = THEM))))
    }
}
