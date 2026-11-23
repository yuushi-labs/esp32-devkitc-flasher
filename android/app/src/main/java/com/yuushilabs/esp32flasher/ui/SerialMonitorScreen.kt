package com.yuushilabs.esp32flasher.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuushilabs.esp32flasher.data.NewlineMode
import com.yuushilabs.esp32flasher.ui.components.LogList
import com.yuushilabs.esp32flasher.ui.components.StatusBadge
import com.yuushilabs.esp32flasher.usb.UsbSerialManager
import kotlinx.coroutines.launch

private val BAUD_RATES = listOf(9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600)

/** Commands the bundled esp32-devkitc-firmware understands (see its `help` output). */
private val QUICK_COMMANDS = listOf("HELP", "PING", "INFO", "PINOUT", "LED ON", "LED OFF", "LED BLINK")

/**
 * Serial monitor: a compact header that survives a 360dp phone, a single horizontally scrolling
 * option bar, a log area that keeps the rest of the screen, and an input row that follows the IME.
 */
@Composable
fun SerialMonitorScreen(
    viewModel: MonitorViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        viewModel.startMonitoring()
        onDispose { viewModel.stopMonitoring() }
    }
    LaunchedEffect(state.message) {
        val message = state.message
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MonitorHeader(
            state = state,
            onReset = viewModel::resetBoard,
            onToggleTimestamps = viewModel::toggleTimestamps,
            onCopyAll = {
                val transcript = state.transcript()
                if (transcript.isNotBlank()) {
                    clipboard.setText(AnnotatedString(transcript))
                    scope.launch {
                        snackbarHostState.showSnackbar("ログ ${state.lines.size} 行をコピーしました")
                    }
                }
            },
            onClear = viewModel::clear,
            onSelectBaud = viewModel::setBaudRate,
        )

        MonitorOptionBar(
            state = state,
            onNewlineMode = viewModel::setNewlineMode,
            onToggleAutoScroll = viewModel::toggleAutoScroll,
            onQuickCommand = viewModel::sendQuickCommand,
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "受信ログ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (state.autoScroll) "${state.lines.size} 行 · 追従中" else "${state.lines.size} 行",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(4.dp))
                LogList(
                    logs = state.lines.map { line ->
                        LogEntry(
                            time = if (state.timestamps) line.time else "",
                            message = line.text,
                            level = line.level,
                        )
                    },
                    autoScroll = state.autoScroll,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }

        MonitorInputBar(
            state = state,
            onInputChange = viewModel::setInput,
            onSend = viewModel::send,
            onReuse = viewModel::setInput,
            onClearHistory = viewModel::clearSendHistory,
        )
    }
}

@Composable
private fun MonitorHeader(
    state: MonitorUiState,
    onReset: () -> Unit,
    onToggleTimestamps: () -> Unit,
    onCopyAll: () -> Unit,
    onClear: () -> Unit,
    onSelectBaud: (Int) -> Unit,
) {
    var baudMenuExpanded by remember { mutableStateOf(false) }
    val connected = state.isConnected

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Filled.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "シリアルモニター",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(
                    text = if (connected) "接続中" else "未接続",
                    containerColor = if (connected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (connected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    connectionText(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    TextButton(
                        onClick = { baudMenuExpanded = true },
                        enabled = connected,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Text(
                            "${state.baudRate}",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = "ボーレート",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = baudMenuExpanded,
                        onDismissRequest = { baudMenuExpanded = false },
                    ) {
                        BAUD_RATES.forEach { rate ->
                            DropdownMenuItem(
                                text = { Text("$rate bps") },
                                onClick = {
                                    baudMenuExpanded = false
                                    onSelectBaud(rate)
                                },
                            )
                        }
                    }
                }
                HeaderIconButton(
                    icon = Icons.Filled.RestartAlt,
                    description = "リセット (EN)",
                    enabled = connected,
                    onClick = onReset,
                )
                HeaderIconButton(
                    icon = Icons.Filled.Schedule,
                    description = "タイムスタンプ",
                    tint = if (state.timestamps) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    onClick = onToggleTimestamps,
                )
                HeaderIconButton(
                    icon = Icons.Filled.ContentCopy,
                    description = "ログ全文をコピー",
                    enabled = state.lines.isNotEmpty(),
                    onClick = onCopyAll,
                )
                HeaderIconButton(
                    icon = Icons.Filled.DeleteSweep,
                    description = "ログをクリア",
                    enabled = state.lines.isNotEmpty(),
                    onClick = onClear,
                )
            }
        }
    }
}

@Composable
private fun HeaderIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(36.dp),
        colors = IconButtonDefaults.iconButtonColors(contentColor = tint),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun MonitorOptionBar(
    state: MonitorUiState,
    onNewlineMode: (NewlineMode) -> Unit,
    onToggleAutoScroll: () -> Unit,
    onQuickCommand: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NewlineMode.entries.forEach { mode ->
            FilterChip(
                selected = state.newlineMode == mode,
                onClick = { onNewlineMode(mode) },
                label = {
                    Text(mode.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                },
            )
        }
        FilterChip(
            selected = state.autoScroll,
            onClick = onToggleAutoScroll,
            label = {
                Text("自動追従", style = MaterialTheme.typography.labelMedium, maxLines = 1)
            },
        )
        Spacer(Modifier.width(2.dp))
        QUICK_COMMANDS.forEach { command ->
            AssistChip(
                onClick = { onQuickCommand(command) },
                enabled = state.isConnected,
                label = {
                    Text(
                        command,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

@Composable
private fun MonitorInputBar(
    state: MonitorUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onReuse: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.sendHistory.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "履歴",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.sendHistory.forEach { past ->
                    AssistChip(
                        onClick = { onReuse(past) },
                        label = {
                            Text(
                                past,
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 140.dp),
                            )
                        },
                    )
                }
                TextButton(
                    onClick = onClearHistory,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Text("履歴を消す", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("送信するコマンド", maxLines = 1) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            FilledIconButton(
                onClick = onSend,
                enabled = state.isConnected && state.input.isNotBlank(),
                modifier = Modifier.size(52.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "送信",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private fun connectionText(state: MonitorUiState): String = when (val connection = state.connection) {
    is UsbSerialManager.ConnectionState.Connected ->
        "${connection.deviceLabel} · ${state.baudRate} bps"

    is UsbSerialManager.ConnectionState.Error -> connection.message
    UsbSerialManager.ConnectionState.Disconnected -> "USB デバイス未接続（書き込みタブで接続）"
}
