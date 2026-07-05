package dev.neoneon.flamingo

import android.net.Uri

private const val INVITE_BASE_URL = "https://neoneon.dev/flamingo"

/**
 * Builds the invite URL for the current state of [gameId], matching the shape iOS's
 * `GameURL.encode` produces for a game-action message (no `lan` — this shares the
 * current position, it isn't itself a move).
 */
fun buildInviteUrl(gameId: String, fen: String, playerId: String): Uri =
    Uri.parse("$INVITE_BASE_URL/games/$gameId").buildUpon()
        .appendQueryParameter("fen", fen)
        .appendQueryParameter("pid", playerId)
        .appendQueryParameter("mid", playerId)
        .build()
