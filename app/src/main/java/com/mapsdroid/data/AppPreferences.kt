package com.mapsdroid.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "maps_prefs")

/** Small persisted-settings surface. Currently just the one-time EULA acknowledgement. */
class AppPreferences(private val context: Context) {

    // catch() ensures a DataStore read error still yields a usable value instead of stalling the
    // flow (which would leave the UI gated on a null state — i.e. a blank screen).
    val eulaAccepted: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[EULA_ACCEPTED] ?: false }

    suspend fun setEulaAccepted(accepted: Boolean) {
        context.dataStore.edit { it[EULA_ACCEPTED] = accepted }
    }

    private companion object {
        val EULA_ACCEPTED = booleanPreferencesKey("eula_accepted")
    }
}
