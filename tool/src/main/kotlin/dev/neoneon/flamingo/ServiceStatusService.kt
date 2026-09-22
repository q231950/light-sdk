package dev.neoneon.flamingo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * Where the published status document comes from.
 *
 * Two URLs, tried in order. The second is the Pages project's own hostname, which does not depend
 * on the `neoneon.dev` zone resolving — so a DNS mistake on the domain the emergency is about
 * cannot also take away the way to say so.
 */
internal interface ServiceStatusSource {
    /** Null for every failure: unreachable, malformed, or the wrong shape. */
    suspend fun fetchDocument(): ServiceStatusDocument?
}

internal class HttpServiceStatusSource(
    private val urls: List<String> = listOf(PRIMARY_URL, FALLBACK_URL),
) : ServiceStatusSource {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            // This runs on the launch path, where waiting on it would be worse than not knowing.
            requestTimeoutMillis = 5_000
            connectTimeoutMillis = 5_000
        }
    }

    override suspend fun fetchDocument(): ServiceStatusDocument? {
        for (url in urls) {
            val document = runCatching {
                val response = client.get(url)
                if (!response.status.isSuccess()) return@runCatching null
                json.decodeFromString<ServiceStatusDocument>(response.bodyAsText())
            }.getOrNull()
            if (document != null) return document
        }
        return null
    }

    fun close() {
        client.close()
    }

    companion object {
        const val PRIMARY_URL = "https://status.neoneon.dev/flamingo.json"
        const val FALLBACK_URL = "https://flamingo-status.pages.dev/flamingo.json"
    }
}

private val statusLevelKey = stringPreferencesKey("FLAMINGO_SERVICE_STATUS_LEVEL")
private val statusTitleKey = stringPreferencesKey("FLAMINGO_SERVICE_STATUS_TITLE")
private val statusBodyKey = stringPreferencesKey("FLAMINGO_SERVICE_STATUS_BODY")
private val statusFetchedAtKey = longPreferencesKey("FLAMINGO_SERVICE_STATUS_FETCHED_AT")
private val statusRecheckAfterKey = longPreferencesKey("FLAMINGO_SERVICE_STATUS_RECHECK_AFTER")

/**
 * Reads the status document, remembers what it said, and decides when to ask again.
 *
 * The rule that matters is **fail open**: anything unexpected resolves to
 * [ServiceStatus.UNRESTRICTED]. The document lives on Cloudflare's edge, so if it cannot be
 * fetched the phone's own connection is the likelier cause — and this phone is often without
 * one. Taking the tool away over that would be a worse outage than the one this defends against.
 *
 * The one exception is a cached restriction: it stays authoritative until `recheckAfterSeconds`
 * has elapsed, so reopening the tool mid-outage does not show the games list and then take it
 * away. Once that window passes with nothing to read, the restriction lifts rather than
 * stranding an install forever.
 *
 * Only the level and the wording are cached. The feature flags are re-derived from the level, so
 * a cache written by an older build cannot resurrect a combination this one does not expect.
 */
internal class ServiceStatusService(
    private val dataStore: DataStore<Preferences>,
    private val source: ServiceStatusSource = HttpServiceStatusSource(),
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /** The status to draw: the cached one while it is still authoritative, otherwise a fresh read. */
    suspend fun current(): ServiceStatus = unexpiredCachedStatus() ?: refresh()

    /**
     * Reads the document, whatever the cache says.
     *
     * On a failed read a cached restriction still stands while it is unexpired — losing the
     * network is not news that the emergency is over.
     */
    suspend fun refresh(): ServiceStatus {
        val document = source.fetchDocument()
            ?: return unexpiredCachedStatus() ?: ServiceStatus.UNRESTRICTED

        // `appVersion` is null because this tool carries no version string to compare: nothing
        // sets `versionName`, so there is no honest answer to "are we below the minimum?" and
        // guessing one could refuse to run a build that is perfectly fine. The check is skipped
        // rather than faked — wire it here once the tool is versioned.
        val status = document.resolve(
            platform = ServiceStatusDocument.PLATFORM_LIGHT_PHONE,
            appVersion = null,
        )
        cache(status)
        return status
    }

    private suspend fun cache(status: ServiceStatus) {
        dataStore.edit { prefs ->
            prefs[statusLevelKey] = status.level.wire
            prefs[statusTitleKey] = status.message.title
            prefs[statusBodyKey] = status.message.body
            prefs[statusFetchedAtKey] = now()
            prefs[statusRecheckAfterKey] = status.recheckAfterSeconds.toLong()
        }
    }

    private suspend fun unexpiredCachedStatus(): ServiceStatus? {
        val prefs = dataStore.data.first()
        val level = prefs[statusLevelKey]?.let { wire ->
            ServiceStatus.Level.entries.firstOrNull { it.wire == wire }
        } ?: return null
        val fetchedAt = prefs[statusFetchedAtKey] ?: return null
        val recheckAfter = prefs[statusRecheckAfterKey] ?: return null

        if (now() - fetchedAt >= recheckAfter * 1_000) return null

        val message = ServiceStatus.Message(
            title = prefs[statusTitleKey] ?: ServiceStatus.BUNDLED_MESSAGE.title,
            body = prefs[statusBodyKey] ?: ServiceStatus.BUNDLED_MESSAGE.body,
        )
        // Rebuilt from the level rather than stored, so an old cache cannot hand this build a
        // feature combination it was never meant to see.
        val document = ServiceStatusDocument(schema = 1, status = level.wire, message = message)
        return document.resolve(
            platform = ServiceStatusDocument.PLATFORM_LIGHT_PHONE,
            appVersion = null,
        ).copy(recheckAfterSeconds = recheckAfter.toInt())
    }
}
