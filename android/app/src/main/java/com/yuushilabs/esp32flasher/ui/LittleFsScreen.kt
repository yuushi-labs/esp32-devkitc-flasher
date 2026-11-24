package com.yuushilabs.esp32flasher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuushilabs.esp32flasher.core.LittleFsEntry
import com.yuushilabs.esp32flasher.core.LittleFsPartitionPreset
import com.yuushilabs.esp32flasher.core.PartitionEntry
import com.yuushilabs.esp32flasher.ui.components.InfoRow
import com.yuushilabs.esp32flasher.ui.components.LogList
import com.yuushilabs.esp32flasher.ui.components.SectionCard
import com.yuushilabs.esp32flasher.ui.components.StatusBadge
import com.yuushilabs.esp32flasher.ui.components.stageLabel
import com.yuushilabs.esp32flasher.usb.UsbSerialManager
import kotlin.math.roundToInt

@Composable
fun LittleFsScreen(
    viewModel: LittleFsViewModel,
    flasherViewModel: FlasherViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var previewEntry by remember { mutableStateOf<LittleFsEntry?>(null) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.addFolder(uri)
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.addFiles(uris)
    }
    val saveImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> if (uri != null) viewModel.saveImage(uri) }
    val saveFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> if (uri != null) viewModel.saveDeviceFile(uri) }
    val saveZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> if (uri != null) viewModel.saveDeviceZip(uri) }
    val saveDeviceImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> if (uri != null) viewModel.saveDeviceImage(uri) }

    LaunchedEffect(state.message) {
        val message = state.message
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { LittleFsConnectionCard(state, flasherViewModel) }
        item { PartitionCard(state, viewModel) }
        item {
            FilesCard(
                state = state,
                onPickFolder = { pickFolder.launch(null) },
                onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
                onRemove = viewModel::removeFile,
                onClear = viewModel::clearFiles,
            )
        }
        item {
            GenerateCard(
                state = state,
                onGenerateAndFlash = viewModel::generateAndFlash,
                onSave = { saveImageLauncher.launch("littlefs-${state.partitionSize ?: 0}.bin") },
                onCancel = viewModel::cancel,
            )
        }
        item {
            DeviceReadCard(
                state = state,
                onRead = viewModel::readFromDevice,
                onCancel = viewModel::cancel,
                onpreview = { entry ->
                    previewEntry = entry
                    viewModel.preview(entry)
                },
                onSaveZip = { saveZipLauncher.launch("littlefs-backup.zip") },
                onSaveImage = { saveDeviceImageLauncher.launch("littlefs-readback.bin") },
            )
        }
        item {
            SectionCard(
                title = "ログ",
                trailing = { TextButton(onClick = viewModel::clearLogs) { Text("クリア") } },
            ) {
                LogList(logs = state.logs, modifier = Modifier.heightIn(min = 80.dp, max = 220.dp))
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    val previewPath = state.previewPath
    if (previewEntry != null && previewPath != null) {
        PreviewDialog(
            state = state,
            onDismiss = {
                previewEntry = null
                viewModel.closePreview()
            },
            onSave = {
                saveFileLauncher.launch(previewPath.substringAfterLast('/'))
            },
        )
    }
}

@Composable
private fun LittleFsConnectionCard(state: LittleFsUiState, flasherViewModel: FlasherViewModel) {
    val flasherState by flasherViewModel.state.collectAsStateWithLifecycle()
    SectionCard(
        title = "USB 接続",
        subtitle = when (val connection = state.connection) {
            is UsbSerialManager.ConnectionState.Connected ->
                "接続中: ${connection.deviceLabel} (${connection.baudRate} bps)"

            is UsbSerialManager.ConnectionState.Error -> connection.message
            UsbSerialManager.ConnectionState.Disconnected -> "ESP32-DevKitC を USB OTG で接続してください"
        },
        trailing = {
            IconButton(onClick = flasherViewModel::refreshDevices) {
                Icon(Icons.Filled.Refresh, contentDescription = "USB デバイスを再スキャン")
            }
        },
    ) {
        if (!state.isConnected) {
            val device = flasherState.devices.firstOrNull { it.isSupported }
            if (device == null) {
                Text(
                    "USB シリアルデバイスが見つかりません。OTG 接続を ON にしてケーブルを確認してください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Button(onClick = { flasherViewModel.connect(device) }) {
                    Icon(Icons.Filled.Usb, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("接続する")
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = flasherViewModel::disconnect,
                    enabled = !state.busy && !state.readingDevice,
                ) { Text("切断") }
                OutlinedButton(
                    onClick = flasherViewModel::pulseReset,
                    enabled = !state.busy && !state.readingDevice,
                ) { Text("リセット (EN)") }
            }
            if (state.busy || state.readingDevice) {
                Text(
                    "転送中は切断できません。完了するか中止してください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.chipInfo?.let { InfoRow("チップ", it.displayName) }
    }
}

@Composable
private fun PartitionCard(state: LittleFsUiState, viewModel: LittleFsViewModel) {
    SectionCard(
        title = "パーティション",
        subtitle = "LittleFS を置く領域。ESP32 の標準パーティションテーブルに合わせたプリセットがあります",
    ) {
        LittleFsPartitionPreset.entries.forEach { preset ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = { viewModel.selectPreset(preset) },
                    label = { Text(preset.label) },
                    leadingIcon = if (state.preset == preset) {
                        { Icon(Icons.Filled.Memory, contentDescription = null) }
                    } else {
                        null
                    },
                )
                Text(
                    if (preset == LittleFsPartitionPreset.CUSTOM) preset.note else preset.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedTextField(
            value = state.offsetInput,
            onValueChange = viewModel::setOffsetInput,
            label = { Text("オフセット") },
            isError = state.offsetError != null,
            supportingText = { Text(state.offsetError ?: "16 進数 (例: 0x290000)") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.sizeInput,
            onValueChange = viewModel::setSizeInput,
            label = { Text("サイズ") },
            isError = state.sizeError != null,
            supportingText = { Text(state.sizeError ?: "16 進数。4 KB の倍数で指定してください") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val table = state.partitionTable
        if (table != null) {
            HorizontalDivider()
            Text("ESP32 のパーティション", style = MaterialTheme.typography.labelLarge)
            Text(
                "MD5: " + when (table.md5Valid) {
                    true -> "OK"
                    false -> "不一致 (テーブルが壊れている可能性)"
                    null -> "なし"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (table.md5Valid == false) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            table.entries.take(12).forEach { entry -> PartitionRow(entry, state, viewModel) }
            if (table.entries.size > 12) {
                Text(
                    "ほか ${table.entries.size - 12} 件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PartitionRow(
    entry: PartitionEntry,
    state: LittleFsUiState,
    viewModel: LittleFsViewModel,
) {
    val selected = state.offsetInput.equals("0x%X".format(entry.offset), ignoreCase = true)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.isFilesystem) { viewModel.applyDetectedPartition(entry) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusBadge(
            text = entry.subtypeLabel,
            containerColor = if (entry.isFilesystem) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (entry.isFilesystem) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "%s + %s".format(formatHex(entry.offset), formatSize(entry.size)),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Text("選択中", style = MaterialTheme.typography.labelSmall)
        } else if (entry.isFilesystem) {
            Text("タップで選択", style = MaterialTheme.typography.labelSmall)
        } else {
            Text("表示のみ", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun FilesCard(
    state: LittleFsUiState,
    onPickFolder: () -> Unit,
    onPickFiles: () -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    SectionCard(
        title = "スマホのフォルダ・ファイル",
        subtitle = "選んだファイルを LittleFS v2 イメージにまとめます",
        trailing = {
            if (state.files.isNotEmpty()) {
                TextButton(onClick = onClear) { Text("全クリア") }
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPickFolder) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("フォルダを選ぶ")
            }
            OutlinedButton(onClick = onPickFiles) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("ファイルを追加")
            }
        }
        if (state.filesLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        val plan = state.plan
        val capacity = state.partitionSize?.toLong() ?: 0L
        if (plan != null || capacity > 0L) {
            val used = plan?.usedBytes ?: 0L
            val ratio = if (capacity > 0L) (used.toFloat() / capacity).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp),
                color = if (state.planError != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${formatSize(used)} / ${formatSize(capacity)} (${(ratio * 100).roundToInt()} %)",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "${state.files.size} ファイル / ${formatSize(state.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.planError?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.files.isEmpty()) {
            Text(
                "フォルダを選ぶと中のファイルをまとめて追加します。ファイルを追加は複数選択できます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp),
            ) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(state.files) { file ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Filled.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    file.path,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    formatSize(file.sizeBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onRemove(file.path) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "削除")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GenerateCard(
    state: LittleFsUiState,
    onGenerateAndFlash: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    SectionCard(title = "生成して書き込む", subtitle = stageLabel(state.stage)) {
        if (state.busy || state.progress > 0f) {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${(state.progress * 100).roundToInt()} %", style = MaterialTheme.typography.bodySmall)
                Text("${state.speedKbps} KB/s", style = MaterialTheme.typography.bodySmall)
            }
        }
        state.busyLabel?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onGenerateAndFlash,
                enabled = state.canGenerate && state.isConnected,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Upload, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (state.busy) "処理中…" else "生成して書き込む")
            }
            if (state.busy && state.cancellable) {
                OutlinedButton(onClick = onCancel) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("中止")
                }
            }
        }
        OutlinedButton(onClick = onSave, enabled = state.canGenerate) {
            Icon(Icons.Filled.Save, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(".bin として保存")
        }
        when {
            !state.isConnected -> Text(
                "ESP32 に接続するとこのイメージを書き込めます (.bin 保存は接続なしでも可)。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.files.isEmpty() -> Text(
                "先にフォルダかファイルを追加してください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceReadCard(
    state: LittleFsUiState,
    onRead: () -> Unit,
    onCancel: () -> Unit,
    onpreview: (LittleFsEntry) -> Unit,
    onSaveZip: () -> Unit,
    onSaveImage: () -> Unit,
) {
    SectionCard(
        title = "ESP32 から読み取る",
        subtitle = "パーティションテーブルを読んで LittleFS の中身を一覧・吸い出しします",
    ) {
        if (state.readingDevice) {
            LinearProgressIndicator(
                progress = { state.readProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${(state.readProgress * 100).roundToInt()} %",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("${state.speedKbps} KB/s", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onRead,
                enabled = state.isConnected && !state.readingDevice && !state.busy,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (state.readingDevice) "読み出し中…" else "ESP32 から読み取る")
            }
            if (state.readingDevice) {
                OutlinedButton(onClick = onCancel) { Text("中止") }
            }
        }
        if (state.deviceImageSize > 0L) {
            InfoRow("読み出したサイズ", formatSize(state.deviceImageSize))
            InfoRow("LittleFS", state.filesystemLabel)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSaveZip) {
                    Icon(Icons.Filled.Save, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("ZIP で保存")
                }
                OutlinedButton(onClick = onSaveImage) {
                    Icon(Icons.Filled.Save, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(".bin を保存")
                }
            }
        }
        if (state.deviceFiles.isEmpty()) {
            if (state.deviceImageSize == 0L) {
                Text(
                    "ESP32 の LittleFS 領域を読み出すと、中のファイルをここに一覧表示します。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "パーティションは空です。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp),
            ) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(state.deviceFiles) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !entry.isDirectory) { onpreview(entry) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Filled.Folder,
                                contentDescription = null,
                                tint = if (entry.isDirectory) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    entry.path,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                                if (!entry.isDirectory) {
                                    Text(
                                        formatSize(entry.size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (!entry.isDirectory) {
                                Text("タップで表示", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewDialog(state: LittleFsUiState, onDismiss: () -> Unit, onSave: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.previewPath ?: "プレビュー") },
        confirmButton = { TextButton(onClick = onSave) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val text = state.previewText
                if (text != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(8.dp),
                    ) {
                        Text(
                            text,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                } else {
                    Text(
                        "テキストではないため中身は表示できません。保存して確認してください。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.previewTruncated) {
                    Text(
                        "先頭のみ表示しています (保存すると全体を書き出します)。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        },
    )
}

private fun formatHex(value: Long): String = "0x%X".format(value)

private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
