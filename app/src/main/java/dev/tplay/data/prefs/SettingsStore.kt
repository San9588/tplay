package dev.tplay.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "tplay_settings")

data class Settings(
    val shuffle: Boolean = false,
    val repeat: Int = 0,
    val playbackSpeed: Float = 1f,
    val bufferMs: Int = 50_000,
    val accent: String = "orange",
    val systemAccent: Boolean = false,
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val SHUFFLE = booleanPreferencesKey("shuffle")
        val REPEAT = intPreferencesKey("repeat")
        val SPEED = doublePreferencesKey("speed")
        val BUFFER = intPreferencesKey("buffer")
        val ACCENT = androidx.datastore.preferences.core.stringPreferencesKey("accent")
        val SYSTEM_ACCENT = booleanPreferencesKey("system_accent")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            shuffle = prefs[Keys.SHUFFLE] ?: false,
            repeat = prefs[Keys.REPEAT] ?: 0,
            playbackSpeed = (prefs[Keys.SPEED] ?: 1.0).toFloat(),
            bufferMs = prefs[Keys.BUFFER] ?: 50_000,
            accent = prefs[Keys.ACCENT] ?: "orange",
            systemAccent = prefs[Keys.SYSTEM_ACCENT] ?: false,
        )
    }

    suspend fun setShuffle(value: Boolean) = context.dataStore.edit {
        it[Keys.SHUFFLE] = value
    }

    suspend fun setRepeat(value: Int) = context.dataStore.edit {
        it[Keys.REPEAT] = value
    }

    suspend fun setSpeed(value: Float) = context.dataStore.edit {
        it[Keys.SPEED] = value.toDouble()
    }

    suspend fun setBuffer(ms: Int) = context.dataStore.edit {
        it[Keys.BUFFER] = ms
    }

    suspend fun setAccent(name: String) = context.dataStore.edit {
        it[Keys.ACCENT] = name
    }

    suspend fun setSystemAccent(enabled: Boolean) = context.dataStore.edit {
        it[Keys.SYSTEM_ACCENT] = enabled
    }
}
