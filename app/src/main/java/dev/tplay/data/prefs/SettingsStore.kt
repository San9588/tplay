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
    val systemAccent: Boolean = true,
    val wifiQuality: String = "HIGH",
    val mobileQuality: String = "LOW",
    val vbassFreq: Int = 100,
    val asciiCols: Int = 96,
    val searchMode: String = "mix",
    val coverMode: String = "ascii",
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val SHUFFLE = booleanPreferencesKey("shuffle")
        val REPEAT = intPreferencesKey("repeat")
        val SPEED = doublePreferencesKey("speed")
        val BUFFER = intPreferencesKey("buffer")
        val ACCENT = androidx.datastore.preferences.core.stringPreferencesKey("accent")
        val SYSTEM_ACCENT = booleanPreferencesKey("system_accent")
        val WIFI_QUALITY = androidx.datastore.preferences.core.stringPreferencesKey("wifi_quality")
        val MOBILE_QUALITY = androidx.datastore.preferences.core.stringPreferencesKey("mobile_quality")
        val VBASS_FREQ = intPreferencesKey("vbass_freq")
        val ASCII_COLS = intPreferencesKey("ascii_cols")
        val SEARCH_MODE = androidx.datastore.preferences.core.stringPreferencesKey("search_mode")
        val COVER_MODE = androidx.datastore.preferences.core.stringPreferencesKey("cover_mode")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            shuffle = prefs[Keys.SHUFFLE] ?: false,
            repeat = prefs[Keys.REPEAT] ?: 0,
            playbackSpeed = (prefs[Keys.SPEED] ?: 1.0).toFloat(),
            bufferMs = prefs[Keys.BUFFER] ?: 50_000,
            accent = prefs[Keys.ACCENT] ?: "orange",
            systemAccent = prefs[Keys.SYSTEM_ACCENT] ?: false,
            wifiQuality = prefs[Keys.WIFI_QUALITY] ?: "HIGH",
            mobileQuality = prefs[Keys.MOBILE_QUALITY] ?: "LOW",
            vbassFreq = prefs[Keys.VBASS_FREQ] ?: 100,
            asciiCols = prefs[Keys.ASCII_COLS] ?: 96,
            searchMode = prefs[Keys.SEARCH_MODE] ?: "mix",
            coverMode = prefs[Keys.COVER_MODE] ?: "ascii",
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

    suspend fun setWifiQuality(name: String) = context.dataStore.edit {
        it[Keys.WIFI_QUALITY] = name
    }

    suspend fun setMobileQuality(name: String) = context.dataStore.edit {
        it[Keys.MOBILE_QUALITY] = name
    }

    suspend fun setVbassFreq(hz: Int) = context.dataStore.edit {
        it[Keys.VBASS_FREQ] = hz
    }

    suspend fun setAsciiCols(cols: Int) = context.dataStore.edit {
        it[Keys.ASCII_COLS] = cols
    }

    suspend fun setSearchMode(mode: String) = context.dataStore.edit {
        it[Keys.SEARCH_MODE] = mode
    }

    suspend fun setCoverMode(mode: String) = context.dataStore.edit {
        it[Keys.COVER_MODE] = mode
    }
}
