package dev.neoneon.flamingo

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The "2 hours ago" a games-list row carries under its title. */
class LastActivityTest {

    private val now = Instant.parse("2026-09-20T12:00:00Z")

    private fun agoBy(seconds: Long) = relativeTime(now.minusSeconds(seconds), now)

    @Test
    fun readsInTheLargestWholeUnit() {
        assertEquals("just now", agoBy(0))
        assertEquals("just now", agoBy(59))
        assertEquals("1 minute ago", agoBy(60))
        assertEquals("59 minutes ago", agoBy(59 * 60))
        assertEquals("1 hour ago", agoBy(60 * 60))
        assertEquals("2 hours ago", agoBy(2 * 60 * 60 + 30 * 60))
        assertEquals("1 day ago", agoBy(24 * 60 * 60))
        assertEquals("6 days ago", agoBy(6 * 24 * 60 * 60))
        assertEquals("1 week ago", agoBy(7 * 24 * 60 * 60))
        assertEquals("4 weeks ago", agoBy(29 * 24 * 60 * 60))
        assertEquals("1 month ago", agoBy(30 * 24 * 60 * 60))
        assertEquals("1 year ago", agoBy(365L * 24 * 60 * 60))
    }

    /** A phone's clock and a server's disagree by seconds; that isn't worth a second vocabulary. */
    @Test
    fun readsAFutureInstantAsJustNow() {
        assertEquals("just now", relativeTime(now.plusSeconds(30), now))
        assertEquals("just now", relativeTime(now.plusSeconds(60 * 60), now))
    }

    @Test
    fun tellsAGamesLastActivityFromItsUpdatedAt() {
        assertEquals("3 hours ago", game(updatedAt = "2026-09-20T09:00:00Z").lastActivity(now))
    }

    /** An unparseable timestamp leaves the line out rather than printing something wrong. */
    @Test
    fun hasNothingToSayAboutATimestampItCannotParse() {
        assertNull(game(updatedAt = "").lastActivity(now))
        assertNull(game(updatedAt = "2026-09-20 09:00:00").lastActivity(now))
    }

    private fun game(updatedAt: String) = Game(
        id = "4f3a2b1c-0000-0000-0000-000000000001",
        fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        whitePlayerID = "96596872-5fe3-4574-8684-aca1047afa23",
        blackPlayerID = "ab98f326-aa39-4f83-8e15-ea8ecabbfc05",
        status = "active",
        createdAt = "2026-09-17T10:00:00Z",
        updatedAt = updatedAt,
    )
}
