package dev.neoneon.flamingo

import java.security.MessageDigest

/**
 * Client half of the request-signing scheme.
 *
 * Must agree **byte for byte** with neoneon's `Sources/App/Security/SignedRequest.swift` and iOS's
 * `Networking/SignedRequest.swift`. A disagreement surfaces only as an opaque `401`, which is why
 * all three assert the same fixture — see `SignedRequestPayloadTest` and
 * `neoneon/docs/flamingo-api.md`. The scheme is specified in
 * `neoneon/docs/adr/ADR-001-signed-personal-data-edits.md`.
 */
internal object SignedRequestPayload {

    const val VERSION = "flamingo-v1"

    const val HEADER_PLAYER = "X-Flamingo-Player"
    const val HEADER_TIMESTAMP = "X-Flamingo-Timestamp"
    const val HEADER_NONCE = "X-Flamingo-Nonce"
    const val HEADER_SIGNATURE = "X-Flamingo-Signature"

    /**
     * The exact bytes that get signed.
     *
     * [playerId] is **upper-cased**. This client mints lowercase [java.util.UUID] strings while
     * Swift's `uuidString` is uppercase, so without normalizing here the two clients would sign
     * different bytes for the same player and both be rejected — the same case mismatch
     * [samePlayer] exists for, except that this one would surface as an unexplained 401.
     *
     * [path] is **not** normalized: sign the path actually being sent. A lowercase id inside the
     * URL is fine as long as it is consistent within the one request, which it is because the
     * same string builds both.
     */
    fun canonical(
        method: String,
        path: String,
        playerId: String,
        timestamp: Long,
        nonce: String,
        body: String,
    ): String = listOf(
        VERSION,
        method.uppercase(),
        path,
        playerId.uppercase(),
        timestamp.toString(),
        nonce,
        sha256Hex(body.toByteArray(Charsets.UTF_8)),
    ).joinToString("\n")

    /** Lower-case hex SHA-256, the spelling all three implementations produce. */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
