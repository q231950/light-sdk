package dev.neoneon.flamingo

import android.net.Uri

private const val INVITE_HOST = "neoneon.dev"
private const val INVITE_BASE_URL = "https://$INVITE_HOST/flamingo"

/**
 * Builds the invite URL for [gameId]'s last move, matching the shape iOS's
 * `GameURL.encode` produces for a real chess move: [preMoveFen] is the position the
 * move was played *from* and [lan] is the move itself — iOS applies `lan` to `fen` to
 * reach the current position, exactly like every other message in this protocol.
 * Sharing the post-move fen with no `lan` would leave iOS with nothing to apply.
 */
fun buildInviteUrl(gameId: String, preMoveFen: String, lan: String, playerId: String): Uri =
    Uri.parse("$INVITE_BASE_URL/games/$gameId").buildUpon()
        .appendQueryParameter("fen", preMoveFen)
        .appendQueryParameter("lan", lan)
        .appendQueryParameter("pid", playerId)
        .appendQueryParameter("mid", playerId)
        .build()

/**
 * The pieces read back off an invite URL when a second phone accepts a game.
 * Only [gameId] is required to join — the game state is fetched from the server;
 * [preMoveFen]/[lan]/[whitePlayerId] are carried for parity with the invite iOS
 * emits (see [buildInviteUrl]) and may be absent.
 */
data class InviteParams(
    val gameId: String,
    val preMoveFen: String?,
    val lan: String?,
    val whitePlayerId: String?,
)

/**
 * Parses a copied invite URL (the shape [buildInviteUrl] produces) back into its
 * parts, or returns null if [raw] isn't a Flamingo game invite. The game id is the
 * last path segment beneath `/games/`; `fen`/`lan`/`pid` are optional query params.
 */
fun parseInviteUrl(raw: String): InviteParams? {
    val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
    if (uri.host != INVITE_HOST) return null

    val segments = uri.pathSegments
    // Expect the invite path ".../flamingo/games/{gameId}"; the id is the tail and
    // must be immediately preceded by "games", so a bare "/games" won't match.
    val gamesIndex = segments.indexOf("games")
    if (gamesIndex < 0 || gamesIndex != segments.lastIndex - 1) return null
    val gameId = segments.last().takeIf { it.isNotBlank() } ?: return null

    return InviteParams(
        gameId = gameId,
        preMoveFen = uri.getQueryParameter("fen"),
        lan = uri.getQueryParameter("lan"),
        whitePlayerId = uri.getQueryParameter("pid"),
    )
}
