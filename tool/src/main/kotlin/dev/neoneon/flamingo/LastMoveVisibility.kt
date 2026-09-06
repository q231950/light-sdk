package dev.neoneon.flamingo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** How the board marks the most recently played move while a game is still in progress. */
enum class LastMoveVisibility(val label: String) {
    Latest("Show latest move"),
    OpponentOnly("Show opponent's last move"),
    Hidden("Do not show last move"),
}

private val lastMoveVisibilityKey = stringPreferencesKey("FLAMINGO_LAST_MOVE_VISIBILITY")

/**
 * Reads [raw] back into a [LastMoveVisibility], defaulting to [LastMoveVisibility.OpponentOnly] —
 * both for a fresh install (no value stored yet) and for anything unrecognized, so a future rename
 * of an entry degrades to the default rather than crashing.
 */
internal fun parseLastMoveVisibility(raw: String?): LastMoveVisibility =
    LastMoveVisibility.entries.firstOrNull { it.name == raw } ?: LastMoveVisibility.OpponentOnly

/**
 * Persists the last-move visibility setting for this tool installation.
 *
 * Reactive rather than one-shot (contrast [PlayerIdentityStore]'s `getOrCreate`): a change made on
 * the settings screen needs to reach a [GameViewViewModel] that's already on the back stack, the
 * same way [com.thelightphone.sdk.LightPushManager]'s `pushCredentialsFlow` reads live off its
 * `DataStore` rather than being fetched once.
 */
class LastMoveVisibilityStore(private val dataStore: DataStore<Preferences>) {
    val flow: Flow<LastMoveVisibility> =
        dataStore.data.map { parseLastMoveVisibility(it[lastMoveVisibilityKey]) }

    suspend fun update(value: LastMoveVisibility) {
        dataStore.edit { prefs -> prefs[lastMoveVisibilityKey] = value.name }
    }
}
