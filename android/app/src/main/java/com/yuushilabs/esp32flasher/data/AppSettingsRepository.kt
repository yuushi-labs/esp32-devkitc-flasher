package com.yuushilabs.esp32flasher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Line ending appended to commands sent from the serial monitor. */
enum class NewlineMode(val label: String, val suffix: ByteArray) {
    NONE("なし", ByteArray(0)),
    LF("LF", byteArrayOf(0x0A)),
    CRLF("CRLF", byteArrayOf(0x0D, 0x0A)),
}

data class AppSettings(
    val repository: String,
    val repositoryHistory: List<String>,
    val monitorBaudRate: Int,
    val flashBaudRate: Int,
    val compression: Boolean,
    val autoReset: Boolean,
    val lastAddress: String,
    val newlineMode: NewlineMode,
    val githubToken: String,
    val checkUpdatesOnStart: Boolean,
    val monitorTimestamps: Boolean,
) {
    companion object {
        const val DEFAULT_MONITOR_BAUD = 115200
        const val DEFAULT_FLASH_BAUD = 460800
        const val DEFAULT_ADDRESS = "0x10000"
        const val MAX_HISTORY = 8
    }
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "esp32_flasher_settings")

class AppSettingsRepository(
    context: Context,
    private val defaultRepository: String,
) {
    private val appContext = context.applicationContext

    val settings: Flow<AppSettings> = appContext.settingsDataStore.data.map { preferences ->
        AppSettings(
            repository = preferences[KEY_REPOSITORY]?.takeIf { it.isNotBlank() } ?: defaultRepository,
            repositoryHistory = decodeHistory(preferences[KEY_HISTORY]),
            monitorBaudRate = preferences[KEY_MONITOR_BAUD] ?: AppSettings.DEFAULT_MONITOR_BAUD,
            flashBaudRate = preferences[KEY_FLASH_BAUD] ?: AppSettings.DEFAULT_FLASH_BAUD,
            compression = preferences[KEY_COMPRESSION] ?: true,
            autoReset = preferences[KEY_AUTO_RESET] ?: true,
            lastAddress = preferences[KEY_LAST_ADDRESS] ?: AppSettings.DEFAULT_ADDRESS,
            newlineMode = runCatching {
                NewlineMode.valueOf(preferences[KEY_NEWLINE] ?: NewlineMode.LF.name)
            }.getOrDefault(NewlineMode.LF),
            githubToken = preferences[KEY_TOKEN].orEmpty(),
            checkUpdatesOnStart = preferences[KEY_CHECK_UPDATES] ?: true,
            monitorTimestamps = preferences[KEY_TIMESTAMPS] ?: true,
        )
    }

    suspend fun updateRepository(repository: String) {
        val trimmed = repository.trim()
        if (trimmed.isEmpty()) return
        appContext.settingsDataStore.edit { preferences ->
            preferences[KEY_REPOSITORY] = trimmed
            val history = decodeHistory(preferences[KEY_HISTORY])
            val updated = (listOf(trimmed) + history.filterNot { it.equals(trimmed, ignoreCase = true) })
                .take(AppSettings.MAX_HISTORY)
            preferences[KEY_HISTORY] = encodeHistory(updated)
        }
    }

    suspend fun setMonitorBaudRate(baudRate: Int) {
        appContext.settingsDataStore.edit { it[KEY_MONITOR_BAUD] = baudRate }
    }

    suspend fun setFlashBaudRate(baudRate: Int) {
        appContext.settingsDataStore.edit { it[KEY_FLASH_BAUD] = baudRate }
    }

    suspend fun setCompression(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[KEY_COMPRESSION] = enabled }
    }

    suspend fun setAutoReset(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[KEY_AUTO_RESET] = enabled }
    }

    suspend fun setLastAddress(address: String) {
        appContext.settingsDataStore.edit { it[KEY_LAST_ADDRESS] = address }
    }

    suspend fun setNewlineMode(mode: NewlineMode) {
        appContext.settingsDataStore.edit { it[KEY_NEWLINE] = mode.name }
    }

    suspend fun setGithubToken(token: String) {
        appContext.settingsDataStore.edit { it[KEY_TOKEN] = token.trim() }
    }

    suspend fun setCheckUpdatesOnStart(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[KEY_CHECK_UPDATES] = enabled }
    }

    suspend fun setMonitorTimestamps(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[KEY_TIMESTAMPS] = enabled }
    }

    suspend fun clearHistory() {
        appContext.settingsDataStore.edit { it.remove(KEY_HISTORY) }
    }

    suspend fun resetToDefaults() {
        appContext.settingsDataStore.edit { preferences ->
            val token = preferences[KEY_TOKEN]
            preferences.clear()
            if (!token.isNullOrBlank()) preferences[KEY_TOKEN] = token
        }
    }

    private fun decodeHistory(value: String?): List<String> =
        value?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    private fun encodeHistory(history: List<String>): String = history.joinToString("\n")

    private companion object {
        val KEY_REPOSITORY = stringPreferencesKey("repository")
        val KEY_HISTORY = stringPreferencesKey("repository_history")
        val KEY_MONITOR_BAUD = intPreferencesKey("monitor_baud_rate")
        val KEY_FLASH_BAUD = intPreferencesKey("flash_baud_rate")
        val KEY_COMPRESSION = booleanPreferencesKey("compression")
        val KEY_AUTO_RESET = booleanPreferencesKey("auto_reset")
        val KEY_LAST_ADDRESS = stringPreferencesKey("last_address")
        val KEY_NEWLINE = stringPreferencesKey("newline_mode")
        val KEY_TOKEN = stringPreferencesKey("github_token")
        val KEY_CHECK_UPDATES = booleanPreferencesKey("check_updates")
        val KEY_TIMESTAMPS = booleanPreferencesKey("monitor_timestamps")
    }
}
