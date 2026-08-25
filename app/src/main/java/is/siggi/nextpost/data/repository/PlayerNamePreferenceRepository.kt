package `is`.siggi.nextpost.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.playerNameDataStore: DataStore<Preferences> by preferencesDataStore(name = "player_prefs")
private val LAST_NAME_KEY = stringPreferencesKey("last_player_name")

/**
 * Section 5.3: the join screen prefills the name field with the last name this *device* used,
 * so a returning player joins with two taps. Deliberately device-scoped, not per-uid like
 * [MapTypePreferenceRepository] — a player name isn't tied to an account the way a creator's
 * map preference is, and section 5.3 describes it as remembered "on this device."
 */
class PlayerNamePreferenceRepository(private val context: Context) {
    fun lastName(): Flow<String> =
        context.playerNameDataStore.data.map { prefs -> prefs[LAST_NAME_KEY] ?: "" }

    suspend fun saveLastName(name: String) {
        context.playerNameDataStore.edit { prefs -> prefs[LAST_NAME_KEY] = name }
    }
}
