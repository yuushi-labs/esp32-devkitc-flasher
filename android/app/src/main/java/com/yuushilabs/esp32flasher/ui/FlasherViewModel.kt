package com.yuushilabs.esp32flasher.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yuushilabs.esp32flasher.AppContainer
import com.yuushilabs.esp32flasher.BuildConfig
import com.yuushilabs.esp32flasher.core.AddressPreset
import com.yuushilabs.esp32flasher.core.FirmwareRelease
import com.yuushilabs.esp32flasher.core.FlashImage
import com.yuushilabs.esp32flasher.core.FlashManifest
import com.yuushilabs.esp32flasher.core.FlashManifestParser
import com.yuushilabs.esp32flasher.core.HexAddressParser
import com.yuushilabs.esp32flasher.core.ReleaseAsset
import com.yuushilabs.esp32flasher.core.Sha256Status
import com.yuushilabs.esp32flasher.data.AppSettings
import com.yuushilabs.esp32flasher.github.ChecksumMismatchException
import com.yuushilabs.esp32flasher.github.RepoRef
import com.yuushilabs.esp32flasher.usb.ChipInfo
import com.yuushilabs.esp32flasher.usb.EspFlasherEngine
import com.yuushilabs.esp32flasher.usb.FlashConfig
import com.yuushilabs.esp32flasher.usb.FlashListener
import com.yuushilabs.esp32flasher.usb.FlashOutcome
import com.yuushilabs.esp32flasher.usb.FlashProgress
import com.yuushilabs.esp32flasher.usb.FlashStage
import com.yuushilabs.esp32flasher.usb.UsbSerialManager
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LogLevel { INFO, SUCCESS, WARN, ERROR }

data class LogEntry(
    val time: String,
    val message: String,
    val level: LogLevel = LogLevel.INFO,
)

sealed interface FirmwareSelection {
    data class ReleaseImage(
        val release: FirmwareRelease,
        val image: FlashImage,
        val asset: ReleaseAsset,
    ) : FirmwareSelection

    data class LocalFile(
        val uri: Uri,
        val fileName: String,
        val sizeBytes: Long,
    ) : FirmwareSelection
}

data class FlasherUiState(
    val devices: List<UsbSerialManager.DeviceEntry> = emptyList(),
    val connection: UsbSerialManager.ConnectionState = UsbSerialManager.ConnectionState.Disconnected,
    val releases: List<FirmwareRelease> = emptyList(),
    val releasesLoading: Boolean = false,
    val releasesError: String? = null,
    val selectedRelease: FirmwareRelease? = null,
    val manifest: FlashManifest? = null,
    val manifestLoading: Boolean = false,
    val selection: FirmwareSelection? = null,
    val addressInput: String = AppSettings.DEFAULT_ADDRESS,
    val addressError: String? = null,
    val compression: Boolean = true,
    val flashing: Boolean = false,
    val downloadProgress: Float? = null,
    val flashProgress: Float = 0f,
    val flashSpeedKbps: Int = 0,
    val flashBytesWritten: Long = 0,
    val flashTotalBytes: Long = 0,
    val stage: FlashStage? = null,
    val chipInfo: ChipInfo? = null,
    val logs: List<LogEntry> = emptyList(),
    val message: String? = null,
) {
    val isConnected: Boolean get() = connection is UsbSerialManager.ConnectionState.Connected

    val canFlash: Boolean
        get() = isConnected && selection != null && !flashing && addressError == null
}

class FlasherViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(FlasherUiState())
    val state: StateFlow<FlasherUiState> = _state.asStateFlow()

    @Volatile
    private var settings: AppSettings? = null

    @Volatile
    private var cancelRequested = false

    private var flashJob: Job? = null
    private var addressInitialized = false
    private var releasesRequested = false

    /** Bumped whenever the user picks another release or file, so stale downloads are dropped. */
    private var selectionGeneration = 0

    private val listener = object : FlashListener {
        override fun onStage(stage: FlashStage) {
            _state.update { it.copy(stage = stage) }
        }

        override fun onLog(message: String) {
            appendLog(message)
        }

        override fun onProgress(progress: FlashProgress) {
            _state.update {
                it.copy(
                    flashProgress = progress.percent / 100f,
                    flashSpeedKbps = progress.kilobytesPerSecond,
                    flashBytesWritten = progress.bytesWritten,
                    flashTotalBytes = progress.totalBytes,
                )
            }
        }
    }

    init {
        container.usbManager.refreshDevices()
        viewModelScope.launch {
            container.usbManager.devices.collect { devices ->
                _state.update { it.copy(devices = devices) }
            }
        }
        viewModelScope.launch {
            container.usbManager.state.collect { connection ->
                _state.update { it.copy(connection = connection) }
            }
        }
        viewModelScope.launch {
            container.settingsRepository.settings.collect { loaded ->
                settings = loaded
                _state.update { current ->
                    current.copy(
                        compression = loaded.compression,
                        addressInput = if (!addressInitialized) loaded.lastAddress else current.addressInput,
                    )
                }
                addressInitialized = true
            }
        }
    }

    fun refreshDevices() {
        container.usbManager.refreshDevices()
    }

    fun connect(entry: UsbSerialManager.DeviceEntry) {
        viewModelScope.launch {
            // Open at the ROM's sync rate: the monitor runs at it too, and the engine raises the
            // link to the flash rate only after the bootloader answered.
            val flashBaudRate = settings?.flashBaudRate ?: AppSettings.DEFAULT_FLASH_BAUD
            val openBaudRate = EspFlasherEngine.INITIAL_SYNC_BAUD
            appendLog("USB アクセス権を要求しています: ${entry.label}")
            val granted = container.usbManager.requestPermission(entry.device)
            if (!granted) {
                appendLog("USB アクセス権が拒否されました", LogLevel.ERROR)
                postMessage("USB アクセス権が拒否されました")
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                container.usbManager.open(entry.device, openBaudRate)
            }
            result.onSuccess {
                appendLog(
                    "接続しました: ${it.deviceLabel} ($openBaudRate bps で接続 / 書き込み時は $flashBaudRate bps)",
                    LogLevel.SUCCESS,
                )
            }.onFailure {
                appendLog("接続に失敗しました: ${it.message}", LogLevel.ERROR)
                postMessage(it.message ?: "接続に失敗しました")
            }
        }
    }

    fun disconnect() {
        container.usbManager.close()
        appendLog("USB デバイスから切断しました")
    }

    fun pulseReset() {
        viewModelScope.launch(Dispatchers.IO) {
            container.usbManager.pulseReset()
        }
        appendLog("リセット信号 (EN) を送信しました")
    }

    fun loadReleases(force: Boolean = false) {
        if (_state.value.releasesLoading) return
        releasesRequested = true
        _state.update { it.copy(releasesLoading = true, releasesError = null) }
        viewModelScope.launch {
            // The saved repository and token are applied before the first request; otherwise a
            // private repository would answer 404 on the very first launch.
            container.awaitSettingsLoaded()
            val loaded = container.settingsRepository.settings.first()
            val repository = RepoRef.parse(loaded.repository)
            if (repository == null) {
                _state.update { it.copy(releasesLoading = false, releasesError = "リポジトリ設定が不正です") }
                return@launch
            }
            try {
                val result = container.releaseClient.listReleases(repository, forceRefresh = force)
                val releases = result.releases.filter { !it.draft }
                _state.update { it.copy(releasesLoading = false, releases = releases) }
                appendLog(
                    "${repository.slug} の Release を ${releases.size} 件取得しました" +
                        if (result.notModified) " (キャッシュ)" else "",
                )
                if (_state.value.selectedRelease == null) {
                    releases.firstOrNull { it.hasFlashableContent && !it.prerelease }
                        ?.let(::selectRelease)
                }
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        releasesLoading = false,
                        releasesError = error.message ?: "Release の取得に失敗しました",
                    )
                }
                appendLog("Release の取得に失敗しました: ${error.message}", LogLevel.ERROR)
            }
        }
    }

    fun loadReleasesIfNeeded() {
        if (!releasesRequested) loadReleases()
    }

    fun selectRelease(release: FirmwareRelease) {
        val generation = ++selectionGeneration
        // The previous release's image must not stay selected while the new one resolves.
        _state.update {
            it.copy(
                selectedRelease = release,
                manifest = null,
                manifestLoading = false,
                selection = null,
            )
        }
        val manifestAsset = release.manifestAsset
        if (manifestAsset == null) {
            appendLog("${release.tagName}: flash-manifest.json がありません")
            release.primaryBinary?.let { selectAsset(release, it) }
            return
        }
        _state.update { it.copy(manifestLoading = true) }
        viewModelScope.launch {
            try {
                val manifest = container.releaseClient.fetchManifest(manifestAsset)
                if (generation != selectionGeneration) return@launch
                _state.update { it.copy(manifest = manifest, manifestLoading = false) }
                appendLog(
                    "flash-manifest.json を読み込みました " +
                        "(イメージ ${manifest.allImages.size} 件 / ピン ${manifest.pins.size} 件)",
                )
                val image = FlashManifestParser.selectImages(manifest).firstOrNull()
                val asset = image?.let { img ->
                    release.assets.firstOrNull { it.name == img.fileName }
                }
                if (image != null && asset != null) {
                    selectImage(release, image, asset)
                } else if (image != null) {
                    appendLog(
                        "Manifest が参照する ${image.fileName} が Release にありません",
                        LogLevel.WARN,
                    )
                }
            } catch (error: Exception) {
                if (generation != selectionGeneration) return@launch
                _state.update { it.copy(manifestLoading = false) }
                appendLog("Manifest の取得に失敗しました: ${error.message}", LogLevel.WARN)
                release.primaryBinary?.let { selectAsset(release, it) }
            }
        }
    }

    fun selectAsset(release: FirmwareRelease, asset: ReleaseAsset) {
        val address = FlashManifestParser.inferAddress(asset.name)
        val image = FlashImage(
            name = asset.name,
            fileName = asset.name,
            address = address,
            sizeBytes = asset.sizeBytes,
            sha256 = asset.sha256,
            downloadUrl = asset.downloadUrl,
        )
        selectImage(release, image, asset)
    }

    fun selectManifestImage(release: FirmwareRelease, image: FlashImage) {
        val asset = release.assets.firstOrNull { it.name == image.fileName }
        if (asset == null) {
            appendLog("${image.fileName} が Release に含まれていません", LogLevel.WARN)
            return
        }
        selectImage(release, image, asset)
    }

    private fun selectImage(release: FirmwareRelease, image: FlashImage, asset: ReleaseAsset) {
        _state.update {
            it.copy(
                selectedRelease = release,
                selection = FirmwareSelection.ReleaseImage(release, image, asset),
                addressInput = HexAddressParser.format(image.address),
                addressError = null,
            )
        }
        appendLog(
            "${asset.name} を選択しました (${HexAddressParser.format(image.address)})",
            LogLevel.SUCCESS,
        )
    }

    fun selectLocalFile(uri: Uri) {
        selectionGeneration++
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching { queryLocalFile(uri) }.getOrNull()
            }
            if (info == null) {
                appendLog("ファイルの情報を取得できませんでした", LogLevel.ERROR)
                postMessage("ファイルを開けませんでした")
                return@launch
            }
            val (name, size) = info
            _state.update {
                it.copy(
                    selection = FirmwareSelection.LocalFile(uri, name, size),
                    selectedRelease = null,
                    manifest = null,
                    addressInput = HexAddressParser.format(AddressPreset.MERGED.address),
                    addressError = null,
                )
            }
            appendLog("ローカルファイルを選択しました: $name (${formatSize(size)})", LogLevel.SUCCESS)
        }
    }

    fun setAddressInput(text: String) {
        val error = HexAddressParser.parse(text).exceptionOrNull()?.message
        _state.update { it.copy(addressInput = text, addressError = error) }
    }

    fun useAddressPreset(preset: AddressPreset) {
        setAddressInput(HexAddressParser.format(preset.address))
    }

    fun flash() {
        val snapshot = _state.value
        if (snapshot.flashing) return
        val selection = snapshot.selection
        if (selection == null) {
            postMessage("書き込むファームウェアを選択してください")
            return
        }
        val address = HexAddressParser.parse(snapshot.addressInput).getOrElse {
            _state.update { state -> state.copy(addressError = it.message) }
            postMessage("書き込みアドレスが不正です")
            return
        }
        val link = container.usbManager.link
        if (link == null) {
            postMessage("USB デバイスに接続してください")
            return
        }
        val config = FlashConfig(
            compression = settings?.compression ?: true,
            autoReset = settings?.autoReset ?: true,
            targetBaudRate = settings?.flashBaudRate ?: AppSettings.DEFAULT_FLASH_BAUD,
        )
        cancelRequested = false
        _state.update {
            it.copy(
                flashing = true,
                flashProgress = 0f,
                flashSpeedKbps = 0,
                flashBytesWritten = 0,
                flashTotalBytes = 0,
                stage = FlashStage.CONNECTING,
                downloadProgress = null,
            )
        }
        appendLog("書き込みを開始します: ${HexAddressParser.format(address)}")
        flashJob = viewModelScope.launch {
            val outcome = try {
                val bytes = resolveBytes(selection)
                appendLog("イメージサイズ: ${bytes.size} バイト")
                val resumeMonitoring = container.usbManager.beginExclusiveAccess(
                    settings?.monitorBaudRate ?: AppSettings.DEFAULT_MONITOR_BAUD,
                )
                try {
                    val engine = EspFlasherEngine(
                        link = link,
                        config = config,
                        listener = listener,
                        cancellation = { cancelRequested },
                    )
                    withContext(Dispatchers.IO) { engine.flash(bytes, address) }
                } finally {
                    container.usbManager.endExclusiveAccess(resumeMonitoring)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                appendLog("書き込み準備で失敗しました: ${error.message}", LogLevel.ERROR)
                FlashOutcome.Failure(error.message ?: "書き込みに失敗しました", error)
            }

            when (outcome) {
                is FlashOutcome.Success -> {
                    _state.update {
                        it.copy(chipInfo = outcome.chip ?: it.chipInfo, flashProgress = 1f)
                    }
                    appendLog(
                        "書き込み完了: ${outcome.uncompressedBytes} バイト / ${outcome.elapsedMs} ms " +
                            "(${if (outcome.compressed) "圧縮" else "非圧縮"})",
                        LogLevel.SUCCESS,
                    )
                    postMessage("書き込みが完了しました")
                }

                is FlashOutcome.Failure -> {
                    appendLog(outcome.message, LogLevel.ERROR)
                    postMessage(outcome.message)
                }

                FlashOutcome.Cancelled -> {
                    appendLog("書き込みを中止しました", LogLevel.WARN)
                    postMessage("書き込みを中止しました")
                }
            }
            _state.update { it.copy(flashing = false) }
            runCatching {
                container.settingsRepository.setLastAddress(HexAddressParser.format(address))
            }
        }
    }

    fun cancelFlash() {
        cancelRequested = true
        appendLog("中止を要求しました。現在のブロック完了後に停止します", LogLevel.WARN)
    }

    fun clearLogs() {
        _state.update { it.copy(logs = emptyList()) }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    private suspend fun resolveBytes(selection: FirmwareSelection): ByteArray = when (selection) {
        is FirmwareSelection.ReleaseImage -> {
            appendLog("${selection.asset.name} をダウンロードしています…")
            val bytes = container.releaseClient.downloadAsset(selection.asset) { read, total ->
                _state.update {
                    it.copy(downloadProgress = if (total > 0) read.toFloat() / total.toFloat() else null)
                }
            }
            _state.update { it.copy(downloadProgress = null) }
            when (FlashManifestParser.checkSha256(bytes, selection.image.sha256)) {
                Sha256Status.MISMATCH ->
                    throw ChecksumMismatchException("SHA-256 が一致しません: ${selection.asset.name}")

                Sha256Status.NOT_AVAILABLE ->
                    appendLog("Manifest に SHA-256 がないため検証をスキップしました", LogLevel.WARN)

                Sha256Status.VERIFIED ->
                    appendLog("SHA-256 を検証しました: ${selection.asset.name}")
            }
            appendLog("${bytes.size} バイトを取得しました", LogLevel.SUCCESS)
            bytes
        }

        is FirmwareSelection.LocalFile -> withContext(Dispatchers.IO) {
            readLocalFile(selection.uri)
        }
    }

    private fun queryLocalFile(uri: Uri): Pair<String, Long> {
        val resolver = getApplication<Application>().contentResolver
        var name: String? = null
        var size = 0L
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        val displayName = name ?: uri.lastPathSegment ?: "firmware.bin"
        return displayName to size
    }

    private fun readLocalFile(uri: Uri): ByteArray {
        val resolver = getApplication<Application>().contentResolver
        val input = resolver.openInputStream(uri) ?: throw IOException("ファイルを開けませんでした")
        return input.use { stream ->
            // Read in chunks so an oversized pick fails on the limit instead of on the heap.
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_IMAGE_BYTES) {
                    throw IOException("ファイルが大きすぎます (最大 ${MAX_IMAGE_BYTES / 1024 / 1024} MB)")
                }
                out.write(buffer, 0, read)
            }
            val bytes = out.toByteArray()
            if (bytes.isEmpty()) throw IOException("ファイルが空です")
            bytes
        }
    }

    private fun appendLog(message: String, level: LogLevel = LogLevel.INFO) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _state.update {
            it.copy(logs = (it.logs + LogEntry(timestamp, message, level)).takeLast(MAX_LOG_LINES))
        }
    }

    private fun postMessage(message: String) {
        _state.update { it.copy(message = message) }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes <= 0L -> "サイズ不明"
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private companion object {
        const val MAX_LOG_LINES = 400
        const val MAX_IMAGE_BYTES = 32 * 1024 * 1024
    }
}
