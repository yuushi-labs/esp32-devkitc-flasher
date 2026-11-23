package com.yuushilabs.esp32flasher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.yuushilabs.esp32flasher.core.AddressPreset
import com.yuushilabs.esp32flasher.core.FirmwareRelease
import com.yuushilabs.esp32flasher.core.FlashImage
import com.yuushilabs.esp32flasher.core.ReleaseAsset
import com.yuushilabs.esp32flasher.ui.components.InfoRow
import com.yuushilabs.esp32flasher.ui.components.LogList
import com.yuushilabs.esp32flasher.ui.components.PinWiringDialog
import com.yuushilabs.esp32flasher.ui.components.PinWiringGuideCard
import com.yuushilabs.esp32flasher.ui.components.SectionCard
import com.yuushilabs.esp32flasher.ui.components.StatusBadge
import com.yuushilabs.esp32flasher.ui.components.stageLabel
import com.yuushilabs.esp32flasher.usb.FlashStage
import com.yuushilabs.esp32flasher.usb.UsbSerialManager
import kotlin.math.roundToInt

@Composable
fun FlasherScreen(
    viewModel: FlasherViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenMonitor: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showPinDialog by remember { mutableStateOf(false) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.selectLocalFile(uri)
    }

    LaunchedEffect(Unit) { viewModel.loadReleasesIfNeeded() }
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
        item { ConnectionCard(state, viewModel) }
        item {
            FirmwareCard(
                state = state,
                onLoadReleases = viewModel::loadReleases,
                onSelectRelease = viewModel::selectRelease,
                onSelectAsset = viewModel::selectAsset,
                onSelectImage = viewModel::selectManifestImage,
                onOpenPinGuide = { showPinDialog = true },
            )
        }
        item {
            PinWiringGuideCard(
                pins = state.manifest?.pins.orEmpty(),
                notes = state.manifest?.wiringNotes.orEmpty(),
                powerNote = state.manifest?.powerNote,
            )
        }
        item {
            LocalFileCard(
                state = state,
                onPickFile = { pickFile.launch(arrayOf("application/octet-stream", "application/bin", "*/*")) },
                onAddressChange = viewModel::setAddressInput,
                onUsePreset = viewModel::useAddressPreset,
            )
        }
        item { FlashCard(state, viewModel::flash, viewModel::cancelFlash, onOpenMonitor) }
        item {
            SectionCard(
                title = "ログ",
                trailing = {
                    TextButton(onClick = viewModel::clearLogs) { Text("クリア") }
                },
            ) {
                LogList(
                    logs = state.logs,
                    modifier = Modifier.heightIn(min = 80.dp, max = 240.dp),
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showPinDialog) {
        PinWiringDialog(
            pins = state.manifest?.pins.orEmpty(),
            notes = state.manifest?.wiringNotes.orEmpty(),
            powerNote = state.manifest?.powerNote,
            onDismiss = { showPinDialog = false },
        )
    }
}

@Composable
private fun ConnectionCard(state: FlasherUiState, viewModel: FlasherViewModel) {
    val connected = state.connection as? UsbSerialManager.ConnectionState.Connected
    SectionCard(
        title = "USB 接続",
        subtitle = when (val connection = state.connection) {
            is UsbSerialManager.ConnectionState.Connected ->
                "接続中: ${connection.deviceLabel} (${connection.baudRate} bps)"

            is UsbSerialManager.ConnectionState.Error -> connection.message
            UsbSerialManager.ConnectionState.Disconnected ->
                "ESP32-DevKitC を USB OTG で接続してください"
        },
        trailing = {
            IconButton(onClick = viewModel::refreshDevices) {
                Icon(Icons.Filled.Refresh, contentDescription = "USB デバイスを再スキャン")
            }
        },
    ) {
        if (state.devices.isEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    "USB シリアルデバイスが見つかりません。OTG アダプタとケーブルを確認してください。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "OPPO / ColorOS では「設定 > その他の設定 > OTG 接続」を ON にすると認識されます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.devices.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Usb, contentDescription = null)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            buildString {
                                append(if (entry.isSupported) "対応ドライバ" else "未対応のデバイス")
                                append(" / ")
                                append(if (entry.hasPermission) "アクセス許可済み" else "アクセス許可なし")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (connected != null) {
                        OutlinedButton(onClick = viewModel::disconnect) { Text("切断") }
                    } else {
                        Button(
                            onClick = { viewModel.connect(entry) },
                            enabled = entry.isSupported,
                        ) { Text("接続") }
                    }
                }
            }
            if (connected != null) {
                Text(
                    "書き込み時は DTR/RTS で自動的にブートローダーへ入ります。自動で入れない場合は BOOT を押しながら EN を押してから書き込みを再試行してください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = viewModel::pulseReset) { Text("リセット (EN) を送る") }
            }
        }
    }
}

@Composable
private fun FirmwareCard(
    state: FlasherUiState,
    onLoadReleases: (Boolean) -> Unit,
    onSelectRelease: (FirmwareRelease) -> Unit,
    onSelectAsset: (FirmwareRelease, ReleaseAsset) -> Unit,
    onSelectImage: (FirmwareRelease, FlashImage) -> Unit,
    onOpenPinGuide: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    SectionCard(
        title = "GitHub Releases から書き込み",
        subtitle = "登録リポジトリの Release から .bin / flash-manifest.json を取得します",
        trailing = {
            IconButton(onClick = { onLoadReleases(true) }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Release を再取得")
            }
        },
    ) {
        if (state.releasesLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        androidx.compose.foundation.layout.Box {
            OutlinedButton(onClick = { menuExpanded = true }) {
                Text(state.selectedRelease?.let { "${it.tagName} — ${it.displayName}" } ?: "Release を選択")
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                state.releases.forEach { release ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                "${release.tagName} — ${release.displayName}" +
                                    if (release.prerelease) " (pre)" else "",
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onSelectRelease(release)
                        },
                    )
                }
            }
        }
        state.releasesError?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        val release = state.selectedRelease
        if (release == null) {
            Text(
                "Release を選択すると flash-manifest.json を解析して書き込み対象を自動選択します。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        HorizontalDivider()
        InfoRow("タグ", release.tagName)
        release.publishedAt?.let { InfoRow("公開日", it.take(10)) }
        release.assets.firstOrNull { it.isManifest }?.let { InfoRow("Manifest", it.name) }

        val manifest = state.manifest
        if (state.manifestLoading) {
            Text("flash-manifest.json を解析しています…", style = MaterialTheme.typography.bodySmall)
        }
        if (manifest != null) {
            if (manifest.version != null) InfoRow("バージョン", manifest.version)
            if (manifest.chip != null) InfoRow("チップ", manifest.chip)
            if (manifest.flashSize != null) InfoRow("フラッシュ", manifest.flashSize)
            Text("書き込みイメージ", style = MaterialTheme.typography.labelLarge)
            manifest.allImages.forEach { image ->
                val selected = (state.selection as? FirmwareSelection.ReleaseImage)?.image?.fileName == image.fileName
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(image.fileName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${image.addressLabel}" + (image.sizeBytes?.let { " / $it bytes" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (selected) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "選択中",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        TextButton(onClick = { onSelectImage(release, image) }) { Text("選択") }
                    }
                }
            }
            if (manifest.hasWiringGuide) {
                TextButton(onClick = onOpenPinGuide) {
                    Icon(Icons.Filled.Memory, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("ピン配線ガイドを開く (${manifest.pins.size} ピン)")
                }
            }
        }

        if (release.binaryAssets.isNotEmpty()) {
            HorizontalDivider()
            Text("Release の .bin アセット", style = MaterialTheme.typography.labelLarge)
            release.binaryAssets.forEach { asset ->
                val selected = (state.selection as? FirmwareSelection.ReleaseImage)?.asset?.name == asset.name
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(asset.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            formatBytes(asset.sizeBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (selected) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "選択中",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        TextButton(onClick = { onSelectAsset(release, asset) }) { Text("選択") }
                    }
                }
            }
        }
        if (!release.hasFlashableContent) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Info, contentDescription = null)
                Text(
                    "この Release には .bin / flash-manifest.json がありません。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun LocalFileCard(
    state: FlasherUiState,
    onPickFile: () -> Unit,
    onAddressChange: (String) -> Unit,
    onUsePreset: (AddressPreset) -> Unit,
) {
    SectionCard(title = "ローカル .bin を書き込む", subtitle = "端末内のファームウェアを選択します") {
        OutlinedButton(onClick = onPickFile) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("ファイルを選択")
        }
        when (val selection = state.selection) {
            is FirmwareSelection.LocalFile -> {
                InfoRow("ファイル", selection.fileName)
                InfoRow("サイズ", formatBytes(selection.sizeBytes))
            }

            is FirmwareSelection.ReleaseImage -> {
                InfoRow("選択中", "${selection.release.tagName} / ${selection.image.fileName}")
            }

            null -> Text(
                "Release を選ぶか、端末内の .bin を選択してください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = state.addressInput,
            onValueChange = onAddressChange,
            label = { Text("書き込みアドレス") },
            isError = state.addressError != null,
            supportingText = {
                Text(
                    state.addressError
                        ?: "16 進数。0x0000 = 結合済み / 0x10000 = アプリ単体",
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { onUsePreset(AddressPreset.MERGED) },
                label = { Text("0x0000 結合済み") },
            )
            AssistChip(
                onClick = { onUsePreset(AddressPreset.APP) },
                label = { Text("0x10000 アプリ単体") },
            )
        }
    }
}

@Composable
private fun FlashCard(
    state: FlasherUiState,
    onFlash: () -> Unit,
    onCancel: () -> Unit,
    onOpenMonitor: () -> Unit,
) {
    SectionCard(title = "書き込み", subtitle = stageLabel(state.stage)) {
        val downloadProgress = state.downloadProgress
        if (downloadProgress != null) {
            Text("ファームウェアをダウンロード中…", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(
                progress = { downloadProgress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.flashing || state.flashProgress > 0f) {
            LinearProgressIndicator(
                progress = { state.flashProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${(state.flashProgress * 100).roundToInt()} %",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "${state.flashSpeedKbps} KB/s",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.flashTotalBytes > 0) {
                InfoRow(
                    "転送済み",
                    "${state.flashBytesWritten} / ${state.flashTotalBytes} バイト",
                )
            }
        }
        state.chipInfo?.let { chip ->
            InfoRow("チップ", chip.displayName)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onFlash,
                enabled = state.canFlash,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    if (state.flashing) Icons.Filled.Bolt else Icons.Filled.PlayArrow,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(if (state.flashing) "書き込み中…" else "書き込み開始")
            }
            if (state.flashing) {
                OutlinedButton(onClick = onCancel) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("中止")
                }
            }
        }
        when {
            !state.isConnected -> Text(
                "USB デバイスに接続すると書き込めます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.selection == null -> Text(
                "書き込むファームウェアを選択してください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.stage == FlashStage.DONE) {
            TextButton(onClick = onOpenMonitor) {
                Icon(Icons.Filled.Usb, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("シリアルモニターを開く")
            }
        }
        StatusBadge(
            text = if (state.compression) "zlib 圧縮 (FLASH_DEFL)" else "非圧縮 (FLASH_DATA)",
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "サイズ不明"
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
