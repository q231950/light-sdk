package dev.neoneon.flamingo

import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * When this game last moved, told relative to [now]: `"2 hours ago"`.
 *
 * Relative rather than a date and a clock time, because the question a games list answers is how
 * long a game has been sitting, not what day it was touched on.
 *
 * `updatedAt` is the server's ISO-8601 instant — the backend writes it on every move and every
 * game action, so it is the game's last activity whichever path produced it. Null when it can't
 * be parsed, so a shape this build doesn't expect leaves the line out rather than printing
 * something wrong.
 */
fun Game.lastActivity(now: Instant = Instant.now()): String? =
    updatedAt.toInstantOrNull()?.let { relativeTime(it, now) }

private fun String.toInstantOrNull(): Instant? =
    try {
        Instant.parse(this)
    } catch (_: DateTimeParseException) {
        null
    }

private const val MINUTE = 60L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR
private const val WEEK = 7 * DAY
private const val MONTH = 30 * DAY
private const val YEAR = 365 * DAY

/**
 * A coarse "N units ago", in the largest unit that leaves a whole number.
 *
 * Deliberately approximate — months are 30 days and years 365 — because nothing on this row turns
 * on the difference, and a calendar-exact answer would need a zone this client doesn't have.
 *
 * An instant in the future reads "just now" rather than counting up: the two clocks involved are
 * a phone's and a server's, and a few seconds of skew is not worth a second vocabulary.
 */
internal fun relativeTime(then: Instant, now: Instant): String {
    val seconds = (now.epochSecond - then.epochSecond).coerceAtLeast(0)
    return when {
        seconds < MINUTE -> "just now"
        seconds < HOUR -> ago(seconds / MINUTE, "minute")
        seconds < DAY -> ago(seconds / HOUR, "hour")
        seconds < WEEK -> ago(seconds / DAY, "day")
        seconds < MONTH -> ago(seconds / WEEK, "week")
        seconds < YEAR -> ago(seconds / MONTH, "month")
        else -> ago(seconds / YEAR, "year")
    }
}

private fun ago(count: Long, unit: String): String =
    if (count == 1L) "1 $unit ago" else "$count ${unit}s ago"
