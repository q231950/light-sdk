package dev.neoneon.flamingo

/**
 * A draw offer's standing, from the local player's point of view.
 *
 * The backend has no draw-offer flag: an offer is a move-log entry carrying only the offerer's
 * id (see [Move]), so which of these applies depends on *who* made the last entry — hence every
 * comparison here goes through [samePlayer].
 */
sealed interface DrawOffer {
    /** Nothing outstanding. */
    data object None : DrawOffer

    /** We offered; waiting on their reply. The board stays playable — they may just move. */
    data object Outgoing : DrawOffer

    /** They offered; we owe an answer. Freezes the board until we accept or decline. */
    data object Incoming : DrawOffer

    /** They turned our offer down. Informational only; cleared by our next move. */
    data object Declined : DrawOffer
}

/**
 * What a game's move log says about draws.
 *
 * How a game *ended* is [GameOutcome]'s job, which reads chesskit's verdict on the board — but an
 * agreed draw is invisible there (no move produces it), so it has to be carried out of the log
 * alongside the offer's standing and handed to [gameOutcome].
 */
internal data class GameActionState(
    val drawOffer: DrawOffer = DrawOffer.None,
    val agreedDraw: Boolean = false,
)

/**
 * Reads a game's draw standing out of its move log, as [localPlayerId] sees it.
 *
 * Draw offers and their replies are recorded as move-log entries with no LAN and one actor id, so
 * this is the only way to recover them after a restart or a re-sync — the live socket alone only
 * reports what arrives while we're connected. [moves] must already be ordered (see
 * `GameViewViewModel.loadExistingGame`).
 *
 * Our own actions come back in the log too, which is why the actor of each entry is compared with
 * [samePlayer] — the server echoes ids uppercase — to tell an offer we're waiting on from one we
 * owe an answer to.
 *
 * An offer only stands while it's the *last* entry: under FIDE rules an offer lapses once the
 * opponent replies or plays, and both of those append an entry after it.
 */
internal fun gameActionState(moves: List<Move>, localPlayerId: String?): GameActionState {
    // An agreed draw ends the game whoever accepted, so the acceptor's identity doesn't matter —
    // and it settles the offer that produced it, leaving nothing outstanding to answer.
    if (moves.any { it.drawAcceptPlayerID != null }) return GameActionState(agreedDraw = true)

    val last = moves.lastOrNull() ?: return GameActionState()
    last.drawOfferPlayerID?.let { offerer ->
        return GameActionState(
            drawOffer = if (samePlayer(offerer, localPlayerId)) DrawOffer.Outgoing else DrawOffer.Incoming,
        )
    }
    last.drawDeclinePlayerID?.let { decliner ->
        // Only worth surfacing when they turned *us* down — our own decline needs no report.
        if (!samePlayer(decliner, localPlayerId)) return GameActionState(drawOffer = DrawOffer.Declined)
    }
    return GameActionState()
}

/**
 * The highest entry number in [moves] — the sequence the next entry we write must follow.
 *
 * `moveNumber` is a *log* sequence, not a half-move count, and draw/resign entries consume one of
 * their own: the recorder is idempotent per number, returning the stored entry and writing nothing
 * when a number is reused. So an accept numbered the same as the offer it answers is silently
 * swallowed — as is the next real move, if a draw entry is left out of this count. Draw entries
 * therefore push subsequent moves' numbers past their true half-move index, which costs nothing:
 * the number only orders the log and keys that idempotency, while replay works off LAN and the
 * turn is read from the FEN.
 */
internal fun lastLogEntryNumber(moves: List<Move>): Int =
    moves.maxOfOrNull { it.moveNumber } ?: 0
