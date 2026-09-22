package dev.neoneon.flamingo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * The status document is parsed and resolved by three independent clients, and the rules that
 * differ between them only matter during an emergency — the worst possible time to discover a
 * disagreement. [resolvesTheSharedFixtureForThisPlatform] uses the same fixture asserted in
 * neoneon and in the iOS app; it is the contract.
 */
class ServiceStatusTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun document(raw: String) = json.decodeFromString<ServiceStatusDocument>(raw)

    /** Verbatim from neoneon's `docs/flamingo-api.md`, "Service status". */
    private val sharedFixture = """
        {
          "schema": 1,
          "status": "readOnly",
          "message": {
            "title": "#flamingo chess is resting",
            "body": "New games are paused for a while. Your games are safe.",
            "actionLabel": "What's going on?",
            "actionURL": "https://neoneon.dev/flamingo/support"
          },
          "features": { "liveSocket": true },
          "pollSecondsFloor": 45,
          "recheckAfterSeconds": 600,
          "minimumVersion": { "ios": "1.2.0", "lightPhone": "1.1.0" },
          "clients": {
            "lightPhone": {
              "status": "disabled",
              "message": { "title": "Resting", "body": "Back soon." }
            }
          },
          "somethingFromTheFuture": true
        }
    """.trimIndent()

    @Test
    fun resolvesTheSharedFixtureForThisPlatform() {
        val status = document(sharedFixture).resolve(ServiceStatusDocument.PLATFORM_LIGHT_PHONE)

        assertEquals(ServiceStatus.Level.DISABLED, status.level)
        // `disabled` is absolute: the top-level `"liveSocket": true` must not leak through it.
        assertEquals(false, status.features.liveSocket)
        assertEquals(false, status.features.newGames)
        assertEquals(false, status.features.invites)
        assertEquals("Resting", status.message.title)
        assertEquals("Back soon.", status.message.body)
        // Inherited from the top level, which the client block did not restate.
        assertEquals(45, status.pollSecondsFloor)
        assertEquals(600, status.recheckAfterSeconds)
    }

    /**
     * The same fixture read as the other client. Worth asserting here too: this is the case where
     * an explicit feature beats the derivation, and the two platforms must disagree in exactly
     * the way the document says.
     */
    @Test
    fun resolvesTheSharedFixtureForTheOtherPlatform() {
        val status = document(sharedFixture)
            .resolve(ServiceStatusDocument.PLATFORM_IOS, appVersion = "1.10.0")

        assertEquals(ServiceStatus.Level.READ_ONLY, status.level)
        assertEquals(true, status.features.liveSocket)
        assertEquals(false, status.features.newGames)
        assertEquals(false, status.features.invites)
        assertEquals("#flamingo chess is resting", status.message.title)
        assertEquals("What's going on?", status.message.actionLabel)
        assertEquals("https://neoneon.dev/flamingo/support", status.message.actionUrl)
    }

    @Test
    fun derivesFeaturesFromTheLevelAlone() {
        val ok = document("""{ "schema": 1, "status": "ok" }""").resolve()
        assertEquals(ServiceStatus.Features(liveSocket = true, newGames = true, invites = true), ok.features)

        // The two-line panic edit: everything still playable, but the socket goes.
        val degraded = document("""{ "schema": 1, "status": "degraded" }""").resolve()
        assertEquals(
            ServiceStatus.Features(liveSocket = false, newGames = true, invites = true),
            degraded.features,
        )

        val readOnly = document("""{ "schema": 1, "status": "readOnly" }""").resolve()
        assertEquals(
            ServiceStatus.Features(liveSocket = false, newGames = false, invites = false),
            readOnly.features,
        )
    }

    @Test
    fun disabledIgnoresExplicitFeatures() {
        val status = document(
            """
            {
              "schema": 1,
              "status": "disabled",
              "features": { "liveSocket": true, "newGames": true, "invites": true }
            }
            """.trimIndent(),
        ).resolve()

        assertEquals(ServiceStatus.Level.DISABLED, status.level)
        assertEquals(
            ServiceStatus.Features(liveSocket = false, newGames = false, invites = false),
            status.features,
        )
    }

    /** A mode invented after this build shipped must not take the tool down with it. */
    @Test
    fun readsAnUnknownLevelAsOk() {
        val status = document("""{ "schema": 1, "status": "somethingNewEntirely" }""").resolve()

        assertEquals(ServiceStatus.Level.OK, status.level)
        assertEquals(true, status.features.newGames)
    }

    /** Unknown keys are data from a later version of the document, not an error. */
    @Test
    fun ignoresFieldsItDoesNotKnow() {
        val status = document(
            """{ "schema": 1, "status": "degraded", "somethingFromTheFuture": { "a": 1 } }""",
        ).resolve()

        assertEquals(ServiceStatus.Level.DEGRADED, status.level)
    }

    @Test
    fun fallsBackToBundledCopyWhenNoMessageIsGiven() {
        val status = document("""{ "schema": 1, "status": "degraded" }""").resolve()
        assertEquals(ServiceStatus.BUNDLED_MESSAGE, status.message)
    }

    @Test
    fun clampsOutOfRangeIntervals() {
        val status = document(
            """{ "schema": 1, "status": "degraded", "pollSecondsFloor": 1, "recheckAfterSeconds": 99999 }""",
        ).resolve()

        assertEquals(10, status.pollSecondsFloor)
        assertEquals(3600, status.recheckAfterSeconds)
    }

    @Test
    fun usesDefaultsWhenIntervalsAreAbsent() {
        val status = document("""{ "schema": 1, "status": "ok" }""").resolve()
        assertEquals(ServiceStatus.DEFAULT_POLL_SECONDS_FLOOR, status.pollSecondsFloor)
        assertEquals(ServiceStatus.DEFAULT_RECHECK_AFTER_SECONDS, status.recheckAfterSeconds)
    }

    @Test
    fun treatsABuildBelowTheMinimumAsDisabled() {
        val raw = """{ "schema": 1, "status": "ok", "minimumVersion": { "lightPhone": "2.0.0" } }"""

        val old = document(raw).resolve(appVersion = "1.10.0")
        assertEquals(ServiceStatus.Level.DISABLED, old.level)
        assertEquals(ServiceStatusDocument.UPDATE_REQUIRED_MESSAGE, old.message)

        val current = document(raw).resolve(appVersion = "2.0.0")
        assertEquals(ServiceStatus.Level.OK, current.level)
    }

    /**
     * This tool carries no version string today, so the caller passes null and the check is
     * skipped rather than guessed at. Refusing to run a build we cannot identify would be a
     * self-inflicted outage.
     */
    @Test
    fun skipsTheMinimumVersionCheckWhenTheBuildHasNoVersion() {
        val status = document(
            """{ "schema": 1, "status": "ok", "minimumVersion": { "lightPhone": "99.0.0" } }""",
        ).resolve(appVersion = null)

        assertEquals(ServiceStatus.Level.OK, status.level)
    }

    /** Another platform's floor says nothing about ours. */
    @Test
    fun ignoresAnotherPlatformsMinimumVersion() {
        val status = document(
            """{ "schema": 1, "status": "ok", "minimumVersion": { "ios": "99.0.0" } }""",
        ).resolve(appVersion = "1.0.0")

        assertEquals(ServiceStatus.Level.OK, status.level)
    }

    @Test
    fun comparesVersionsComponentwise() {
        assertTrue(compareVersions("1.2.0", "1.2") == 0)
        assertTrue(compareVersions("1.10.0", "1.9.0") > 0)
        assertTrue(compareVersions("1.2.3", "1.2.4") < 0)
        // A malformed component counts as zero rather than throwing.
        assertTrue(compareVersions("1.x.0", "1.0.0") == 0)
    }
}
