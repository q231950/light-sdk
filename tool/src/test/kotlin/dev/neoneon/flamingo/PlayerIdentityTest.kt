package dev.neoneon.flamingo

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerIdentityTest {

    // The exact ids from the reported "both players black" game (2A787C32…): the server
    // echoes the white player's id UPPERCASE, while this client stores its own id lowercase
    // (java.util.UUID.randomUUID().toString()). A plain `==` between them never matched, so
    // color always fell through to black.
    private val serverUpper = "96596872-5FE3-4574-8684-ACA1047AFA23"
    private val clientLower = "96596872-5fe3-4574-8684-aca1047afa23"
    private val otherPlayer = "AB98F326-AA39-4F83-8E15-EA8ECABBFC05"

    @Test
    fun matchesSameUuidRegardlessOfCase() {
        assertTrue(samePlayer(serverUpper, clientLower))
        assertTrue(samePlayer(clientLower, serverUpper))
    }

    @Test
    fun matchesIdenticalStrings() {
        assertTrue(samePlayer(serverUpper, serverUpper))
        assertTrue(samePlayer(clientLower, clientLower))
    }

    @Test
    fun rejectsDifferentPlayers() {
        assertFalse(samePlayer(serverUpper, otherPlayer))
        assertFalse(samePlayer(clientLower, otherPlayer))
    }

    @Test
    fun rejectsNulls() {
        assertFalse(samePlayer(null, clientLower))
        assertFalse(samePlayer(clientLower, null))
        assertFalse(samePlayer(null, null))
    }

    @Test
    fun resolvesJoinerAsWhiteAcrossCaseMismatch() {
        // Regression for the reported bug: the joiner filled the white seat, the server
        // returned it uppercase, and the client compared against its lowercase id. The
        // color predicate (whitePlayerID matches me -> white, else black) must say white.
        val whitePlayerIDFromServer = serverUpper
        val myId = clientLower
        val amWhite = samePlayer(whitePlayerIDFromServer, myId)
        assertTrue(amWhite)
    }
}
