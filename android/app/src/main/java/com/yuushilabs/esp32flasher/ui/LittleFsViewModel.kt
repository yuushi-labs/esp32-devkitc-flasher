package com.yuushilabs.esp32flasher.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yuushilabs.esp32flasher.AppContainer
import com.yuushilabs.esp32flasher.core.EspStub
import com.yuushilabs.esp32flasher.core.EspStubParser
import com.yuushilabs.esp32flasher.core.HexAddressParser
import com.yuushilabs.esp32flasher.core.LittleFsBuildException
import com.yuushilabs.esp32flasher.core.LittleFsCapacityException
import com.yuushilabs.esp32flasher.core.LittleFsEntry
import com.yuushilabs.esp32flasher.core.LittleFsFile
import com.yuushilabs.esp32flasher.core.LittleFsFormatException
import com.yuushilabs.esp32flasher.core.LittleFsImageBuilder
import com.yuushilabs.esp32flasher.core.LittleFsImagePlan
import com.yuushilabs.esp32flasher.core.LittleFsPartitionPreset
import com.yuushilabs.esp32flasher.core.LittleFsPlannedFile
import com.yuushilabs.esp32flasher.core.LittleFsReader
import com.yuushilabs.esp32flasher.core.PartitionEntry
import com.yuushilabs.esp32flasher.core.PartitionTable
import com.yuushilabs.esp32flasher.core.PartitionTableParser
import com.yuushilabs.esp32flasher.data.AppSettings
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A file that was picked from the phone and will go into the generated image. */
data class PickedFile(
    val uri: Uri,
    val path: String,
    val sizeBytes: Long,
)

data class LittleFsUiState(
    val connection: UsbSerialManager.ConnectionState = UsbSerialManager.ConnectionState.Disconnected,
    val preset: LittleFsPartitionPreset = LittleFsPartitionPreset.DEFAULT_4MB,
    val offsetInput: String = HexAddressParser.format(LittleFsPartitionPreset.DEFAULT_4MB.offset),
    val sizeInput: String = "0x%X".format(LittleFsPartitionPreset.DEFAULT_4MB.size),
    val offsetError: String? = null,
    val sizeError: String? = null,
    val files: List<PickedFile> = emptyList(),
    val filesLoading: Boolean = false,
    val plan: LittleFsImagePlan? = null,
    val planError: String? = null,
    val busy: Boolean = false,
    val busyLabel: String? = null,
    val progress: Float = 0f,
    val speedKbps: Int = 0,
    val stage: FlashStage? = null,
    val chipInfo: ChipInfo? = null,
    val partitionTable: PartitionTable? = null,
    val deviceFiles: List<LittleFsEntry> = emptyList(),
    val deviceImageSize: Long = 0,
    val readingDevice: Boolean = false,
    val readProgress: Float = 0f,
    val previewPath: String? = null,
    val previewText: String? = null,
    val previewBytes: ByteArray? = null,
    val previewTruncated: Boolean = false,
    val logs: List<LogEntry> = emptyList(),
    val message: String? = null,
) {
    val isConnected: Boolean get() = connection is UsbSerialManager.ConnectionState.Connected

    val offset: Long? get() = if (offsetError == null) HexAddressParser.parse(offsetInput).getOrNull() else null

    val partitionSize: Int?
        get() {
            if (sizeError != null) return null
            val parsed = HexAddressParser.parse(sizeInput).getOrNull() ?: return null
            return parsed.takeIf { it in 4096..(16L * 1024 * 1024) }?.toInt()
        }

    val totalBytes: Long get() = files.sumOf { it.sizeBytes }

    val filesystemLabel: String
        get() = (partitionTable?.preferredFilesystem)?.let { "${it.label} (${it.subtypeLabel})" } ?: "—"

    val canGenerate: Boolean
        get() = files.isNotEmpty() && offset != null && partitionSize != null &&
            planError == null && !busy
}

/**
 * LittleFS mode: builds an image from the phone's files, writes it to the ESP32, and reads a
 * LittleFS partition back out of the chip for browsing and saving.
 */
class LittleFsViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(LittleFsUiState())
    val state: StateFlow<LittleFsUiState> = _state.asStateFlow()

    @Volatile
    private var cancelRequested = false

    private var job: Job? = null
    private var stub: EspStub? = null
    private var lastImage: ByteArray? = null

    private val listener = object : FlashListener {
        override fun onStage(stage: FlashStage) {
            _state.update { it.copy(stage = stage) }
        }

        override fun onLog(message: String) {
            appendLog(message)
        }

        override fun onProgress(progress: FlashProgress) {
            _state.update {
                if (it.readingDevice) {
                    it.copy(readProgress = progress.percent / 100f, speedKbps = progress.kilobytesPerSecond)
                } else {
                    it.copy(progress = progress.percent / 100f, speedKbps = progress.kilobytesPerSecond)
                }
            }
        }
    }

    init {
        viewModelScope.launch {
            container.usbManager.state.collect { connection ->
                _state.update { it.copy(connection = connection) }
            }
        }
        recomputePlan()
    }

    fun selectPreset(preset: LittleFsPartitionPreset) {
        _state.update {
            it.copy(
                preset = preset,
                offsetInput = HexAddressParser.format(preset.offset),
                sizeInput = "0x%X".format(preset.size),
                offsetError = null,
                sizeError = null,
            )
        }
        recomputePlan()
    }

    fun setOffsetInput(text: String) {
        _state.update {
            it.copy(
                preset = LittleFsPartitionPreset.CUSTOM,
                offsetInput = text,
                offsetError = HexAddressParser.parse(text).exceptionOrNull()?.message,
            )
        }
        recomputePlan()
    }

    fun setSizeInput(text: String) {
        val error = parseSize(text)
        _state.update {
            it.copy(
                preset = LittleFsPartitionPreset.CUSTOM,
                sizeInput = text,
                sizeError = error,
            )
        }
        recomputePlan()
    }

    private fun parseSize(text: String): String? {
        val parsed = HexAddressParser.parse(text).getOrElse { return it.message }
        return when {
            parsed < 4096 -> "サイズは 4 KB 以上にしてください"
            parsed % 4096 != 0L -> "サイズは 4 KB (0x1000) の倍数にしてください"
            parsed > 16L * 1024 * 1024 -> "サイズが大きすぎます (最大 16 MB)"
            else -> null
        }
    }

    fun addFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(filesLoading = true) }
            val added = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching { pickedFile(uri, queryName(uri)) }.getOrNull()
                }
            }
            merge(added)
            _state.update { it.copy(filesLoading = false) }
        }
    }

    /** Adds every file below a folder picked with `OpenDocumentTree`, keeping relative paths. */
    fun addFolder(treeUri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(filesLoading = true, busyLabel = "フォルダを読み込んでいます") }
            val collected = withContext(Dispatchers.IO) {
                runCatching { walkTree(treeUri) }.getOrElse { error ->
                    appendLog("フォルダを読めませんでした: ${error.message}", LogLevel.ERROR)
                    emptyList()
                }
            }
            merge(collected)
            _state.update { it.copy(filesLoading = false, busyLabel = null) }
            appendLog("フォルダから ${collected.size} 件のファイルを追加しました", LogLevel.SUCCESS)
        }
    }

    private fun merge(added: List<PickedFile>) {
        if (added.isEmpty()) return
        _state.update { current ->
            val existing = current.files.associateBy { it.path }
            val merged = ArrayList(current.files)
            var duplicates = 0
            for (file in added) {
                if (existing.containsKey(file.path)) {
                    duplicates++
                    continue
                }
                merged += file
            }
            if (duplicates > 0) {
                appendLog("同名のファイル $duplicates 件は追加しませんでした", LogLevel.WARN)
            }
            current.copy(files = merged.sortedBy { it.path })
        }
        recomputePlan()
    }

    fun removeFile(path: String) {
        _state.update { it.copy(files = it.files.filterNot { file -> file.path == path }) }
        recomputePlan()
    }

    fun clearFiles() {
        _state.update { it.copy(files = emptyList()) }
        recomputePlan()
    }

    private fun recomputePlan() {
        val current = _state.value
        val offset = current.offset
        val size = current.partitionSize
        if (size == null || offset == null) {
            _state.update { it.copy(plan = null, planError = null) }
            return
        }
        if (current.files.isEmpty()) {
            _state.update { it.copy(plan = null, planError = null) }
            return
        }
        if (offset + size > MAX_FLASH_BYTES) {
            _state.update {
                it.copy(plan = null, planError = "オフセットとサイズがフラッシュの範囲 (16 MB) を超えています")
            }
            return
        }
        val plan = runCatching {
            LittleFsImageBuilder.planBySize(
                files = current.files.map {
                    LittleFsPlannedFile(it.path, it.sizeBytes.coerceAtLeast(0L))
                },
                partitionSize = size,
            )
        }
        plan.onSuccess { computed ->
            val error = when {
                !computed.fits -> "容量オーバー: 必要 ${formatBytes(computed.usedBytes)} / 上限 ${formatBytes(computed.capacityBytes)}"
                else -> null
            }
            _state.update { it.copy(plan = computed, planError = error) }
        }.onFailure { error ->
            _state.update { it.copy(plan = null, planError = error.message) }
        }
    }

    /** Builds the image from the current file list, then writes it to the chip. */
    fun generateAndFlash() {
        startBusy {
            val current = _state.value
            val offset = current.offset ?: throw LittleFsBuildException("オフセットが不正です")
            val size = current.partitionSize ?: throw LittleFsBuildException("サイズが不正です")
            val files = withContext(Dispatchers.IO) { readFiles(current.files) }
            val image = withContext(Dispatchers.Default) {
                LittleFsImageBuilder.build(files, partitionSize = size)
            }
            appendLog(
                "LittleFS イメージを生成しました: ${formatBytes(image.size.toLong())} / ${files.size} ファイル",
                LogLevel.SUCCESS,
            )
            lastImage = image
            flashImage(image, offset)
        }
    }

    /** Builds the image and hands it to the system file picker. */
    fun saveImage(target: Uri) {
        startBusy {
            val current = _state.value
            val size = current.partitionSize ?: throw LittleFsBuildException("サイズが不正です")
            val files = withContext(Dispatchers.IO) { readFiles(current.files) }
            val image = withContext(Dispatchers.Default) {
                LittleFsImageBuilder.build(files, partitionSize = size)
            }
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(target)?.use {
                    it.write(image)
                } ?: throw IOException("保存先を開けませんでした")
            }
            appendLog("イメージを保存しました (${formatBytes(image.size.toLong())})", LogLevel.SUCCESS)
        }
    }

    private suspend fun flashImage(image: ByteArray, offset: Long) {
        val link = container.usbManager.link ?: throw IOException("USB デバイスに接続してください")
        val settings = container.settingsRepository.settings.first()
        val config = FlashConfig(
            compression = settings?.compression ?: true,
            autoReset = settings?.autoReset ?: true,
            targetBaudRate = settings?.flashBaudRate ?: AppSettings.DEFAULT_FLASH_BAUD,
        )
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
            val outcome = withContext(Dispatchers.IO) { engine.flash(image, offset) }
            when (outcome) {
                is FlashOutcome.Success -> {
                    _state.update { it.copy(chipInfo = outcome.chip ?: it.chipInfo, progress = 1f) }
                    postMessage("LittleFS の書き込みが完了しました")
                }

                is FlashOutcome.Failure -> {
                    appendLog(outcome.message, LogLevel.ERROR)
                    postMessage(outcome.message)
                }

                FlashOutcome.Cancelled -> postMessage("書き込みを中止しました")
            }
        } finally {
            container.usbManager.endExclusiveAccess(resumeMonitoring)
        }
    }

    /** Reads the partition table and the LittleFS partition from the chip. */
    fun readFromDevice() {
        if (_state.value.readingDevice) return
        viewModelScope.launch {
            cancelRequested = false
            _state.update {
                it.copy(
                    readingDevice = true,
                    busy = true,
                    busyLabel = "ESP32 から読み出しています",
                    readProgress = 0f,
                    deviceFiles = emptyList(),
                    partitionTable = null,
                    previewPath = null,
                )
            }
            try {
                val link = container.usbManager.link ?: throw IOException("USB デバイスに接続してください")
                val settings = container.settingsRepository.settings.first()
                val config = FlashConfig(
                    compression = false,
                    autoReset = settings?.autoReset ?: true,
                    targetBaudRate = settings?.flashBaudRate ?: AppSettings.DEFAULT_FLASH_BAUD,
                )
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
                    val tableImage = withContext(Dispatchers.IO) {
                        engine.readFlash(
                            PartitionTableParser.TABLE_OFFSET.toLong(),
                            PartitionTableParser.TABLE_MAX_SIZE,
                            loadStub(),
                        )
                    }
                    val table = withContext(Dispatchers.Default) {
                        runCatching {
                            PartitionTableParser.parseTableImage(
                                tableImage,
                                flashSize = DEFAULT_FLASH_BYTES,
                            )
                        }.getOrNull()
                    }
                    _state.update { it.copy(partitionTable = table, chipInfo = engine.chipInfo) }
                    if (table == null) {
                        appendLog("パーティションテーブルを解析できませんでした", LogLevel.WARN)
                        postMessage("パーティションテーブルを読めませんでした")
                        return@launch
                    }
                    appendLog(
                        "パーティションテーブル: ${table.entries.size} 個 " +
                            "(MD5 ${when (table.md5Valid) { true -> "OK"; false -> "不一致"; null -> "なし" }})",
                        if (table.md5Valid == false) LogLevel.WARN else LogLevel.INFO,
                    )
                    val fs = table.preferredFilesystem
                    if (fs == null) {
                        appendLog("littlefs / spiffs のパーティションがありません", LogLevel.WARN)
                        postMessage("LittleFS パーティションが見つかりません")
                        return@launch
                    }
                    applyDetectedPartition(fs)
                    val size = fs.size.toInt()
                    val image = withContext(Dispatchers.IO) { engine.readFlash(fs.offset, size, loadStub()) }
                    lastImage = image
                    val reader = withContext(Dispatchers.Default) {
                        runCatching { LittleFsReader(image) }.getOrElse { error ->
                            if (fs.isSpiffs) {
                                appendLog("SPIFFS パーティションは LittleFS として読めません", LogLevel.WARN)
                            }
                            throw error
                        }
                    }
                    val entries = withContext(Dispatchers.Default) { reader.list() }
                    _state.update {
                        it.copy(
                            deviceFiles = entries,
                            deviceImageSize = image.size.toLong(),
                            readProgress = 1f,
                        )
                    }
                    appendLog(
                        "${fs.label} から ${entries.count { !it.isDirectory }} ファイルを読み出しました " +
                            "(littlefs ${reader.superblock.versionLabel})",
                        LogLevel.SUCCESS,
                    )
                } finally {
                    container.usbManager.endExclusiveAccess(resumeMonitoring)
                }
            } catch (error: Exception) {
                appendLog("読み出しに失敗しました: ${error.message}", LogLevel.ERROR)
                postMessage(error.message ?: "読み出しに失敗しました")
            } finally {
                _state.update { it.copy(readingDevice = false, busy = false, busyLabel = null) }
            }
        }
    }

    fun applyDetectedPartition(entry: PartitionEntry) {
        _state.update {
            it.copy(
                preset = LittleFsPartitionPreset.CUSTOM,
                offsetInput = HexAddressParser.format(entry.offset),
                sizeInput = "0x%X".format(entry.size),
                offsetError = null,
                sizeError = null,
            )
        }
        recomputePlan()
    }

    fun cancel() {
        cancelRequested = true
        appendLog("中止を要求しました", LogLevel.WARN)
    }

    fun preview(entry: LittleFsEntry) {
        if (entry.isDirectory) return
        val image = lastImage ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val reader = LittleFsReader(image)
                    val bytes = reader.read(entry)
                    val truncated = bytes.size > PREVIEW_LIMIT
                    val slice = if (truncated) bytes.copyOf(PREVIEW_LIMIT) else bytes
                    val text = if (isProbablyText(slice)) String(slice, Charsets.UTF_8) else null
                    Triple(text, if (truncated) null else slice, truncated || text == null)
                }
            }
            result.onSuccess { (text, bytes, truncated) ->
                _state.update {
                    it.copy(
                        previewPath = entry.path,
                        previewText = text,
                        previewBytes = bytes,
                        previewTruncated = truncated,
                    )
                }
            }.onFailure { error ->
                postMessage(error.message ?: "ファイルを読めませんでした")
            }
        }
    }

    fun closePreview() {
        _state.update {
            it.copy(previewPath = null, previewText = null, previewBytes = null, previewTruncated = false)
        }
    }

    fun saveDeviceFile(target: Uri) {
        val image = lastImage
        val path = _state.value.previewPath
        if (image == null || path == null) return
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) { LittleFsReader(image).read(path) }
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(target)?.use {
                        it.write(bytes)
                    } ?: throw IOException("保存先を開けませんでした")
                }
                appendLog("$path を保存しました (${formatBytes(bytes.size.toLong())})", LogLevel.SUCCESS)
            } catch (error: Exception) {
                appendLog("保存に失敗しました: ${error.message}", LogLevel.ERROR)
                postMessage(error.message ?: "保存に失敗しました")
            }
        }
    }

    fun saveDeviceZip(target: Uri) {
        val image = lastImage
        if (image == null) return
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.Default) {
                    val reader = LittleFsReader(image)
                    val files = reader.files()
                    withContext(Dispatchers.IO) {
                        getApplication<Application>().contentResolver.openOutputStream(target)?.use { out ->
                            ZipOutputStream(out).use { zip ->
                                for (entry in files) {
                                    zip.putNextEntry(ZipEntry(entry.path))
                                    zip.write(reader.read(entry))
                                    zip.closeEntry()
                                }
                            }
                        } ?: throw IOException("保存先を開けませんでした")
                    }
                    files.size
                }
                appendLog("$count ファイルを ZIP で保存しました", LogLevel.SUCCESS)
            } catch (error: Exception) {
                appendLog("ZIP 保存に失敗しました: ${error.message}", LogLevel.ERROR)
                postMessage(error.message ?: "ZIP 保存に失敗しました")
            }
        }
    }

    fun saveDeviceImage(target: Uri) {
        val image = lastImage ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(target)?.use {
                        it.write(image)
                    } ?: throw IOException("保存先を開けませんでした")
                }
                appendLog("読み出したイメージを保存しました (${formatBytes(image.size.toLong())})", LogLevel.SUCCESS)
            } catch (error: Exception) {
                appendLog("保存に失敗しました: ${error.message}", LogLevel.ERROR)
                postMessage(error.message ?: "保存に失敗しました")
            }
        }
    }

    fun clearLogs() {
        _state.update { it.copy(logs = emptyList()) }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun startBusy(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        if (_state.value.busy) return
        cancelRequested = false
        _state.update { it.copy(busy = true, progress = 0f, speedKbps = 0) }
        job = viewModelScope.launch {
            try {
                block()
            } catch (error: LittleFsCapacityException) {
                appendLog(error.message ?: "容量が足りません", LogLevel.ERROR)
                postMessage(error.message ?: "容量が足りません")
            } catch (error: LittleFsBuildException) {
                appendLog(error.message ?: "イメージを作成できません", LogLevel.ERROR)
                postMessage(error.message ?: "イメージを作成できません")
            } catch (error: LittleFsFormatException) {
                appendLog(error.message ?: "LittleFS を解析できません", LogLevel.ERROR)
                postMessage(error.message ?: "LittleFS を解析できません")
            } catch (error: Exception) {
                appendLog("失敗しました: ${error.message}", LogLevel.ERROR)
                postMessage(error.message ?: "失敗しました")
            } finally {
                _state.update { it.copy(busy = false, busyLabel = null) }
            }
        }
    }

    private fun loadStub(): EspStub? {
        stub?.let { return it }
        return runCatching {
            val text = getApplication<Application>().assets.open(STUB_ASSET).bufferedReader().use {
                it.readText()
            }
            EspStubParser.parse(text).also { parsed ->
                stub = parsed
                appendLog("スタブフラッシャーを読み込みました (${parsed.totalBytes} バイト)")
            }
        }.getOrElse { error ->
            appendLog("スタブフラッシャーを読めませんでした: ${error.message}", LogLevel.WARN)
            null
        }
    }

    private fun readFiles(files: List<PickedFile>): List<LittleFsFile> {
        val resolver = getApplication<Application>().contentResolver
        return files.map { picked ->
            val bytes = resolver.openInputStream(picked.uri)?.use { it.readBytes() }
                ?: throw IOException("${picked.path} を開けませんでした")
            LittleFsFile(picked.path, bytes)
        }
    }

    private fun pickedFile(uri: Uri, path: String): PickedFile {
        val size = querySize(uri)
        return PickedFile(uri = uri, path = path, sizeBytes = size)
    }

    private fun queryName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        var name: String? = null
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) name = cursor.getString(index)
        }
        return name ?: uri.lastPathSegment ?: "file"
    }

    private fun querySize(uri: Uri): Long {
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                return cursor.getLong(index)
            }
        }
        // Documents that do not report a size are read once to learn it.
        return runCatching {
            resolver.openInputStream(uri)?.use { it.readBytes().size.toLong() }
        }.getOrNull() ?: 0L
    }

    private fun walkTree(treeUri: Uri): List<PickedFile> {
        val resolver = getApplication<Application>().contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = queryName(DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId))
        val out = ArrayList<PickedFile>()
        walkDocument(resolver, treeUri, rootId, if (rootName.isBlank()) "" else rootName, out, 0)
        return out
    }

    private fun walkDocument(
        resolver: ContentResolver,
        treeUri: Uri,
        documentId: String,
        path: String,
        out: MutableList<PickedFile>,
        depth: Int,
    ) {
        if (depth > MAX_FOLDER_DEPTH) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val children = ArrayList<Triple<String, String, Long>>() // id, name, size
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2)
                val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                children += Triple(id, name, if (mime == DocumentsContract.Document.MIME_TYPE_DIR) -1L else size)
            }
        }
        for ((id, name, size) in children.sortedBy { it.second }) {
            val childPath = if (path.isEmpty()) name else "$path/$name"
            if (size < 0) {
                walkDocument(resolver, treeUri, id, childPath, out, depth + 1)
            } else {
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                out += pickedFile(uri, childPath)
            }
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

    private fun formatBytes(bytes: Long): String = formatSize(bytes)

    private fun isProbablyText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        var control = 0
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            if (value == 0) return false
            if (value < 0x09) control++
        }
        return control * 20 < bytes.size
    }

    private companion object {
        const val STUB_ASSET = "esp32-stub-flasher.json"
        const val MAX_LOG_LINES = 200
        const val PREVIEW_LIMIT = 64 * 1024
        const val MAX_FOLDER_DEPTH = 12
        const val DEFAULT_FLASH_BYTES = 4L * 1024 * 1024
        const val MAX_FLASH_BYTES = 16L * 1024 * 1024
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
