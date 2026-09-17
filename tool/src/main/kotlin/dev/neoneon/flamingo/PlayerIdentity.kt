package dev.neoneon.flamingo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val playerIdKey = stringPreferencesKey("FLAMINGO_PLAYER_ID")

/**
 * True if two player-id strings identify the same player, ignoring case.
 *
 * This client mints ids with [UUID.randomUUID] (lowercase), while the server round-trips
 * them through a Swift `UUID` and echoes them back uppercase. A plain `==` between a local
 * id and a server-returned white/black player id therefore never matches — which silently
 * resolved every player's color to black. Compare case-insensitively instead.
 */
internal fun samePlayer(a: String?, b: String?): Boolean =
    a != null && b != null && a.equals(b, ignoreCase = true)

// Superseded by playerIdKey now that an install's single identity can play either color
// (white in games it creates, black in games it accepts by invite). Older installs stored
// their id under this white-only key back when a locally created game was always white;
// reuse it so those games aren't orphaned rather than minting a fresh id.
private val legacyWhitePlayerIdKey = stringPreferencesKey("FLAMINGO_WHITE_PLAYER_ID")

// Set once the server has acknowledged this id, so registration is the one-off call it is meant
// to be rather than a request on every list load.
private val registeredKey = booleanPreferencesKey("FLAMINGO_PLAYER_REGISTERED")

// The display name the server holds for us, cached so the Account screen can draw immediately
// and so a rename shows up without waiting for a round trip.
private val playerNameKey = stringPreferencesKey("FLAMINGO_PLAYER_NAME")

/** Persists a single player ID per tool installation, generating it on first access. */
class PlayerIdentityStore(private val dataStore: DataStore<Preferences>) {
    suspend fun getOrCreate(): String {
        val current = dataStore.data.first()
        current[playerIdKey]?.let { return it }

        val resolved = current[legacyWhitePlayerIdKey] ?: UUID.randomUUID().toString()
        dataStore.edit { prefs -> prefs[playerIdKey] = resolved }
        return resolved
    }

    /** Our display name as last known, or null before the first successful call to the server. */
    val nameFlow: Flow<String?> = dataStore.data.map { it[playerNameKey] }

    suspend fun cachedName(): String? = dataStore.data.first()[playerNameKey]

    /**
     * Registers this install's id with the server once, and caches the name that comes back.
     *
     * A no-op after the first success. A failure — no network on a phone that is often without
     * one — deliberately leaves the flag unset so the next launch tries again; it never blocks
     * or fails the caller, since a nameless games list still works.
     *
     * Registering is safe to repeat in any case: the server returns the name it already holds
     * rather than minting a new one, so a lost flag never costs the player the name they chose.
     *
     * `internal` because [FlamingoApi] is: a public member may not expose an internal type.
     */
    internal suspend fun ensureRegistered(api: FlamingoApi, playerId: String) {
        if (dataStore.data.first()[registeredKey] == true) return
        val registered = api.registerPlayer(playerId).getOrNull() ?: return
        dataStore.edit { prefs ->
            prefs[registeredKey] = true
            prefs[playerNameKey] = registered.name
        }
    }

    /** Records a name the server has accepted, so the UI reflects it without a re-fetch. */
    suspend fun cacheName(name: String) {
        dataStore.edit { prefs -> prefs[playerNameKey] = name }
    }
}
