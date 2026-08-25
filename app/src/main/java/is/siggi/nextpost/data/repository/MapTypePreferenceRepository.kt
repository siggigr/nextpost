package `is`.siggi.nextpost.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.maps.android.compose.MapType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.mapTypeDataStore: DataStore<Preferences> by preferencesDataStore(name = "map_type_prefs")

/**
 * The create screen's map type choice (section 5.2 follow-up), persisted per creator uid
 * rather than just per device: this app is anonymous-auth-per-device today, so in practice
 * that's the same thing, but keying it by uid costs nothing and stays correct if the app ever
 * supports switching accounts on one device. Never touched by the play screen, which stays on
 * [MapType.NORMAL] — see CreateGameScreen's map type control.
 */
class MapTypePreferenceRepository(
    private val context: Context,
    private val auth: FirebaseAuth = Firebase.auth
) {
    fun mapType(): Flow<MapType> = context.mapTypeDataStore.data.map { prefs ->
        val stored = auth.currentUser?.uid?.let { uid -> prefs[key(uid)] }
        stored?.let { name -> runCatching { MapType.valueOf(name) }.getOrNull() } ?: MapType.NORMAL
    }

    suspend fun setMapType(mapType: MapType) {
        val uid = auth.currentUser?.uid ?: return
        context.mapTypeDataStore.edit { prefs -> prefs[key(uid)] = mapType.name }
    }

    private fun key(uid: String) = stringPreferencesKey("map_type_$uid")
}
