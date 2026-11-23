package com.yuushilabs.esp32flasher.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yuushilabs.esp32flasher.AppContainer
import com.yuushilabs.esp32flasher.BuildConfig
import com.yuushilabs.esp32flasher.data.AppSettings
import com.yuushilabs.esp32flasher.github.RepoRef
import com.yuushilabs.esp32flasher.update.UpdateStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings? = null,
    val repositoryInput: String = "",
    val repositoryError: String? = null,
    val updateStatus: UpdateStatus? = null,
    val checkingUpdate: Boolean = false,
    val downloadingUpdate: Boolean = false,
    val updateDownloadProgress: Float = 0f,
    val message: String? = null,
    val appVersion: String = BuildConfig.VERSION_NAME,
    val appVersionCode: Int = BuildConfig.VERSION_CODE,
)

class SettingsViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private var initialized = false

    init {
        viewModelScope.launch {
            container.settingsRepository.settings.collect { settings ->
                _state.update { current ->
                    current.copy(
                        settings = settings,
                        repositoryInput = if (initialized) current.repositoryInput else settings.repository,
                    )
                }
                initialized = true
            }
        }
    }

    fun setRepositoryInput(text: String) {
        _state.update { it.copy(repositoryInput = text, repositoryError = null) }
    }

    fun saveRepository() {
        val input = _state.value.repositoryInput
        val repository = RepoRef.parse(input)
        if (repository == null) {
            _state.update { it.copy(repositoryError = "owner/repo の形式で入力してください") }
            return
        }
        viewModelScope.launch {
            container.settingsRepository.updateRepository(repository.slug)
            _state.update {
                it.copy(
                    repositoryInput = repository.slug,
                    repositoryError = null,
                    message = "${repository.slug} を保存しました",
                )
            }
        }
    }

    fun useHistoryRepository(slug: String) {
        _state.update { it.copy(repositoryInput = slug, repositoryError = null) }
        saveRepository()
    }

    fun clearRepositoryHistory() {
        viewModelScope.launch { container.settingsRepository.clearHistory() }
    }

    fun setMonitorBaudRate(baudRate: Int) {
        viewModelScope.launch { container.settingsRepository.setMonitorBaudRate(baudRate) }
    }

    fun setFlashBaudRate(baudRate: Int) {
        viewModelScope.launch { container.settingsRepository.setFlashBaudRate(baudRate) }
    }

    fun setCompression(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setCompression(enabled) }
    }

    fun setAutoReset(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setAutoReset(enabled) }
    }

    fun setCheckUpdatesOnStart(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setCheckUpdatesOnStart(enabled) }
    }

    fun setMonitorTimestamps(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setMonitorTimestamps(enabled) }
    }

    fun setGithubToken(token: String) {
        viewModelScope.launch { container.settingsRepository.setGithubToken(token) }
    }

    fun resetSettings() {
        viewModelScope.launch {
            container.settingsRepository.resetToDefaults()
            _state.update { it.copy(message = "設定を初期化しました") }
        }
    }

    fun checkForUpdate(force: Boolean = false) {
        if (_state.value.checkingUpdate) return
        _state.update { it.copy(checkingUpdate = true, updateStatus = null) }
        viewModelScope.launch {
            val status = container.updateManager.checkForUpdate(force)
            _state.update { it.copy(checkingUpdate = false, updateStatus = status) }
            if (status is UpdateStatus.Failed) {
                _state.update { it.copy(message = status.message) }
            }
        }
    }

    fun downloadAndInstall() {
        val available = _state.value.updateStatus as? UpdateStatus.Available ?: return
        val apk = available.apk
        if (apk == null) {
            _state.update { it.copy(message = "Release に APK が添付されていません") }
            return
        }
        if (!container.updateManager.canInstallPackages()) {
            container.updateManager.openInstallPermissionSettings()
            _state.update { it.copy(message = "「提供元不明のアプリ」を許可してから再度お試しください") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(downloadingUpdate = true, updateDownloadProgress = 0f) }
            try {
                val file = container.updateManager.downloadApk(apk) { read, total ->
                    _state.update {
                        it.copy(
                            updateDownloadProgress = if (total > 0) {
                                read.toFloat() / total.toFloat()
                            } else {
                                0f
                            },
                        )
                    }
                }
                container.updateManager.installApk(file)
                _state.update { it.copy(message = "インストーラを起動しました") }
            } catch (error: Exception) {
                _state.update {
                    it.copy(message = "アップデートに失敗しました: ${error.message}")
                }
            } finally {
                _state.update { it.copy(downloadingUpdate = false) }
            }
        }
    }

    fun openInstallPermissionSettings() {
        container.updateManager.openInstallPermissionSettings()
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }
}
