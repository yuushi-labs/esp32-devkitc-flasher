package com.yuushilabs.esp32flasher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuushilabs.esp32flasher.ui.components.InfoRow
import com.yuushilabs.esp32flasher.ui.components.SectionCard
import com.yuushilabs.esp32flasher.update.UpdateStatus
import kotlin.math.roundToInt

private val FLASH_BAUD_RATES = listOf(115200, 230400, 460800, 921600)
private val MONITOR_BAUD_RATES = listOf(9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600)

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tokenInput by remember { mutableStateOf("") }

    LaunchedEffect(state.settings?.githubToken) {
        tokenInput = state.settings?.githubToken.orEmpty()
    }
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
        item {
            SectionCard(
                title = "連携リポジトリ",
                subtitle = "owner/repo 形式。flash-manifest.json があると自動で書き込み対象を選択します",
            ) {
                OutlinedTextField(
                    value = state.repositoryInput,
                    onValueChange = viewModel::setRepositoryInput,
                    label = { Text("GitHub リポジトリ") },
                    isError = state.repositoryError != null,
                    supportingText = {
                        state.repositoryError?.let { Text(it) }
                            ?: Text("既定: yuushi-labs/esp32-devkitc-firmware")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = viewModel::saveRepository) { Text("保存") }
                val history = state.settings?.repositoryHistory.orEmpty()
                if (history.isNotEmpty()) {
                    Text("履歴", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        history.take(3).forEach { slug ->
                            AssistChip(
                                onClick = { viewModel.useHistoryRepository(slug) },
                                label = { Text(slug) },
                            )
                        }
                    }
                    TextButton(onClick = viewModel::clearRepositoryHistory) { Text("履歴を消去") }
                }
            }
        }

        item {
            SectionCard(title = "書き込み設定") {
                SwitchRow(
                    label = "zlib 圧縮で書き込む",
                    description = "FLASH_DEFL_BEGIN/DATA/END を使用。失敗時は自動で非圧縮に切り替えます",
                    checked = state.settings?.compression ?: true,
                    onCheckedChange = viewModel::setCompression,
                )
                SwitchRow(
                    label = "DTR/RTS で自動ブートローダー突入",
                    description = "自動リセットが効かないボードでは OFF にして手動で BOOT を押してください",
                    checked = state.settings?.autoReset ?: true,
                    onCheckedChange = viewModel::setAutoReset,
                )
                BaudSelector(
                    label = "書き込みボーレート",
                    current = state.settings?.flashBaudRate ?: 460800,
                    options = FLASH_BAUD_RATES,
                    onSelect = viewModel::setFlashBaudRate,
                )
            }
        }

        item {
            SectionCard(title = "シリアルモニター設定") {
                BaudSelector(
                    label = "モニターボーレート",
                    current = state.settings?.monitorBaudRate ?: 115200,
                    options = MONITOR_BAUD_RATES,
                    onSelect = viewModel::setMonitorBaudRate,
                )
                SwitchRow(
                    label = "タイムスタンプを表示",
                    description = "受信ログの行頭に時刻を表示します",
                    checked = state.settings?.monitorTimestamps ?: true,
                    onCheckedChange = viewModel::setMonitorTimestamps,
                )
            }
        }

        item {
            SectionCard(
                title = "GitHub API トークン (任意)",
                subtitle = "レート制限の緩和やプライベートリポジトリの参照に使用します",
            ) {
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    label = { Text("Personal Access Token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { viewModel.setGithubToken(tokenInput) }) { Text("トークンを保存") }
            }
        }

        item {
            SectionCard(
                title = "アプリのアップデート",
                subtitle = "GitHub Releases から最新 APK を取得して上書きインストールします",
            ) {
                InfoRow("現在のバージョン", "${state.appVersion} (${state.appVersionCode})")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.checkForUpdate(true) },
                        enabled = !state.checkingUpdate,
                    ) {
                        Icon(Icons.Filled.SystemUpdate, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.checkingUpdate) "確認中…" else "更新を確認")
                    }
                    OutlinedButton(onClick = viewModel::openInstallPermissionSettings) {
                        Icon(Icons.Filled.OpenInNew, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("インストール許可")
                    }
                }
                when (val status = state.updateStatus) {
                    null -> Unit
                    UpdateStatus.UpToDate -> Text(
                        "最新バージョンです。",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    UpdateStatus.NotModified -> Text(
                        "最新バージョンです (304 Not Modified)。",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    UpdateStatus.NoRelease -> Text(
                        "Release が見つかりませんでした。",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    is UpdateStatus.Failed -> Text(
                        status.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    is UpdateStatus.Available -> {
                        HorizontalDivider()
                        Text(
                            "v${status.versionName} が利用可能です。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        status.notes?.take(400)?.takeIf { it.isNotBlank() }?.let { notes ->
                            Text(
                                notes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.downloadingUpdate) {
                            LinearProgressIndicator(
                                progress = { state.updateDownloadProgress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "${(state.updateDownloadProgress * 100).roundToInt()} %",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Button(
                                onClick = viewModel::downloadAndInstall,
                                enabled = status.apk != null,
                            ) {
                                Icon(Icons.Filled.CloudDownload, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("ダウンロードして更新")
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = "OPPO / ColorOS での OTG 接続") {
                listOf(
                    "設定 > その他の設定 > OTG 接続 を ON にする",
                    "USB OTG アダプタ (Type-C → USB-A) を使用する",
                    "ESP32-DevKitC の USB ケーブルはデータ対応のものを使用する",
                    "書き込み後もシリアルモニターを使う場合はアプリを終了しない",
                ).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            SectionCard(title = "このアプリについて") {
                InfoRow("パッケージ", "com.yuushilabs.esp32flasher")
                InfoRow("署名", "固定配布鍵 (esp32-flasher.jks)")
                InfoRow("対象", "ESP32-DevKitC / ESP32 ファミリ")
                TextButton(onClick = viewModel::resetSettings) { Text("設定を初期化") }
            }
        }
        item { Spacer(Modifier.width(1.dp)) }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun BaudSelector(
    label: String,
    current: Int,
    options: List<Int>,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        androidx.compose.foundation.layout.Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text("$current bps")
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { rate ->
                    DropdownMenuItem(
                        text = { Text("$rate bps") },
                        onClick = {
                            expanded = false
                            onSelect(rate)
                        },
                    )
                }
            }
        }
    }
}
