package dev.neoneon.flamingo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What `#flamingo chess` is allowed to do right now, and what to tell the player about it.
 *
 * The backend runs on a single machine paid for personally, so success is a failure mode: if the
 * game finds an audience faster than the budget allows, load has to come off somewhere. This is
 * the lever — a static document on Cloudflare's edge, read on launch, saying how much of the
 * service is open.
 *
 * The document deliberately does **not** live on `neoneon.dev`: one you query to find out the
 * server is overwhelmed must not sit behind the overwhelmed server. Its schema is specified in
 * neoneon's `docs/flamingo-api.md` under "Service status", the reasoning is in ADR-002, and the
 * same fixture is asserted in all three repositories.
 *
 * This is the *resolved* form — what this tool should do. The document as published is
 * [ServiceStatusDocument].
 */
data class ServiceStatus(
    val level: Level,
    val message: Message,
    val features: Features,
    val pollSecondsFloor: Int,
    val recheckAfterSeconds: Int,
) {
    /**
     * How much of the service is open, in escalating order of severity.
     *
     * Graduated rather than a boolean on purpose: [DEGRADED] sheds the largest cost — one
     * persistent socket per player — while leaving every game playable, so the cheap mitigation
     * comes first and [DISABLED] stays a last resort.
     */
    enum class Level(val wire: String) {
        OK("ok"),
        DEGRADED("degraded"),
        READ_ONLY("readOnly"),
        DISABLED("disabled");

        companion object {
            /** An unrecognised level is [OK]: this build has to survive a mode invented later. */
            fun from(wire: String?): Level = entries.firstOrNull { it.wire == wire } ?: OK
        }
    }

    @Serializable
    data class Message(
        val title: String,
        val body: String,
        val actionLabel: String? = null,
        @SerialName("actionURL") val actionUrl: String? = null,
    )

    data class Features(
        val liveSocket: Boolean,
        val newGames: Boolean,
        val invites: Boolean,
    )

    companion object {
        const val DEFAULT_POLL_SECONDS_FLOOR = 30
        const val DEFAULT_RECHECK_AFTER_SECONDS = 300

        private val POLL_SECONDS_FLOOR_RANGE = 10..600
        private val RECHECK_AFTER_SECONDS_RANGE = 30..3600

        /**
         * Used when the document gave no wording of its own. It has to make sense at any level,
         * so it says nothing specific about what is wrong.
         */
        val BUNDLED_MESSAGE = Message(
            title = "#flamingo chess is resting",
            body = "The service is busy right now. Your games are safe — try again a little later.",
        )

        /** Everything open: no document, an unreadable one, or one we do not understand. */
        val UNRESTRICTED = ServiceStatus(
            level = Level.OK,
            message = BUNDLED_MESSAGE,
            features = Features(liveSocket = true, newGames = true, invites = true),
            pollSecondsFloor = DEFAULT_POLL_SECONDS_FLOOR,
            recheckAfterSeconds = DEFAULT_RECHECK_AFTER_SECONDS,
        )

        internal fun clampPollSecondsFloor(value: Int?): Int =
            value?.coerceIn(POLL_SECONDS_FLOOR_RANGE) ?: DEFAULT_POLL_SECONDS_FLOOR

        internal fun clampRecheckAfterSeconds(value: Int?): Int =
            value?.coerceIn(RECHECK_AFTER_SECONDS_RANGE) ?: DEFAULT_RECHECK_AFTER_SECONDS
    }
}

/**
 * The document exactly as published, before it is resolved for one platform.
 *
 * Every field but `schema` and `status` is optional: `{ "schema": 1, "status": "degraded" }` is
 * complete and valid, so a panic edit can be two lines. Unknown fields are ignored (the client
 * is configured with `ignoreUnknownKeys`), because a document written for a later version of
 * this tool must still be readable by this one.
 */
@Serializable
data class ServiceStatusDocument(
    val schema: Int,
    /** Kept as the raw string: an unrecognised value is not an error, it is `ok`. */
    val status: String,
    val message: ServiceStatus.Message? = null,
    val features: PartialFeatures? = null,
    val pollSecondsFloor: Int? = null,
    val recheckAfterSeconds: Int? = null,
    val minimumVersion: Map<String, String>? = null,
    val clients: Map<String, Override>? = null,
) {
    /** The subset of fields a per-platform override may restate. */
    @Serializable
    data class Override(
        val status: String? = null,
        val message: ServiceStatus.Message? = null,
        val features: PartialFeatures? = null,
        val pollSecondsFloor: Int? = null,
        val recheckAfterSeconds: Int? = null,
    )

    /** `features` with every key optional — absent means "whatever the level derives". */
    @Serializable
    data class PartialFeatures(
        val liveSocket: Boolean? = null,
        val newGames: Boolean? = null,
        val invites: Boolean? = null,
    )

    /**
     * Resolves the document for one platform.
     *
     * 1. Start from the top level.
     * 2. A `clients[platform]` block replaces the keys it states. `message` is replaced whole — a
     *    half-merged message reads as nonsense — while `features` merges key by key.
     * 3. `features` derives from the resolved level, then explicit keys apply on top.
     * 4. `disabled` turns everything off and ignores `features` entirely. It is absolute.
     * 5. A build below this platform's `minimumVersion` is `disabled`.
     *
     * [appVersion] is null when the caller has no version to offer, and the minimum-version check
     * is then skipped rather than guessed at — see [ServiceStatusService] for why this tool
     * passes null today.
     */
    fun resolve(platform: String = PLATFORM_LIGHT_PHONE, appVersion: String? = null): ServiceStatus {
        val client = clients?.get(platform)

        var level = ServiceStatus.Level.from(client?.status ?: status)
        var message = client?.message ?: this.message

        if (isBelowMinimumVersion(platform, appVersion)) {
            level = ServiceStatus.Level.DISABLED
            // Only reach for the update wording when the document did not supply its own: a
            // document that bothered to write a message meant it for this case too.
            message = message ?: UPDATE_REQUIRED_MESSAGE
        }

        return ServiceStatus(
            level = level,
            message = message ?: ServiceStatus.BUNDLED_MESSAGE,
            features = resolveFeatures(level, client),
            pollSecondsFloor = ServiceStatus.clampPollSecondsFloor(
                client?.pollSecondsFloor ?: pollSecondsFloor
            ),
            recheckAfterSeconds = ServiceStatus.clampRecheckAfterSeconds(
                client?.recheckAfterSeconds ?: recheckAfterSeconds
            ),
        )
    }

    private fun resolveFeatures(
        level: ServiceStatus.Level,
        client: Override?,
    ): ServiceStatus.Features {
        if (level == ServiceStatus.Level.DISABLED) {
            return ServiceStatus.Features(liveSocket = false, newGames = false, invites = false)
        }

        // What the level means on its own, before anything overrides it.
        val derived = when (level) {
            ServiceStatus.Level.OK ->
                ServiceStatus.Features(liveSocket = true, newGames = true, invites = true)
            ServiceStatus.Level.DEGRADED ->
                ServiceStatus.Features(liveSocket = false, newGames = true, invites = true)
            ServiceStatus.Level.READ_ONLY, ServiceStatus.Level.DISABLED ->
                ServiceStatus.Features(liveSocket = false, newGames = false, invites = false)
        }

        return ServiceStatus.Features(
            liveSocket = client?.features?.liveSocket ?: features?.liveSocket ?: derived.liveSocket,
            newGames = client?.features?.newGames ?: features?.newGames ?: derived.newGames,
            invites = client?.features?.invites ?: features?.invites ?: derived.invites,
        )
    }

    private fun isBelowMinimumVersion(platform: String, appVersion: String?): Boolean {
        val minimum = minimumVersion?.get(platform) ?: return false
        if (appVersion == null) return false
        return compareVersions(appVersion, minimum) < 0
    }

    companion object {
        const val PLATFORM_LIGHT_PHONE = "lightPhone"
        const val PLATFORM_IOS = "ios"

        /**
         * Shown when this build is below the document's `minimumVersion`. A shipped app cannot be
         * recalled, so refusing to run is the only lever that reaches a bad release.
         */
        val UPDATE_REQUIRED_MESSAGE = ServiceStatus.Message(
            title = "Time for an update",
            body = "This version of #flamingo chess can no longer connect. Updating puts you back in your games.",
        )
    }
}

/**
 * Compares dotted numeric versions: negative, zero or positive for less, equal, greater.
 *
 * Missing components count as zero, so `"1.2"` and `"1.2.0"` are the same version, and a
 * component that is not a number counts as zero rather than throwing — a malformed version in
 * the document must not decide anything on its own.
 */
internal fun compareVersions(lhs: String, rhs: String): Int {
    val left = lhs.split(".").map { it.toIntOrNull() ?: 0 }
    val right = rhs.split(".").map { it.toIntOrNull() ?: 0 }
    for (index in 0 until maxOf(left.size, right.size)) {
        val l = left.getOrElse(index) { 0 }
        val r = right.getOrElse(index) { 0 }
        if (l != r) return l.compareTo(r)
    }
    return 0
}
