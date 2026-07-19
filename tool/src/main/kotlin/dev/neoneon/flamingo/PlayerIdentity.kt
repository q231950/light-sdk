package dev.neoneon.flamingo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import java.util.UUID

private val playerIdKey = stringPreferencesKey("FLAMINGO_PLAYER_ID")

// Superseded by playerIdKey now that an install's single identity can play either color
// (white in games it creates, black in games it accepts by invite). Older installs stored
// their id under this white-only key back when a locally created game was always white;
// reuse it so those games aren't orphaned rather than minting a fresh id.
private val legacyWhitePlayerIdKey = stringPreferencesKey("FLAMINGO_WHITE_PLAYER_ID")

/** Persists a single player ID per tool installation, generating it on first access. */
class PlayerIdentityStore(private val dataStore: DataStore<Preferences>) {
    suspend fun getOrCreate(): String {
        val current = dataStore.data.first()
        current[playerIdKey]?.let { return it }

        val resolved = current[legacyWhitePlayerIdKey] ?: UUID.randomUUID().toString()
        dataStore.edit { prefs -> prefs[playerIdKey] = resolved }
        return resolved
    }
}
