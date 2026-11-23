package com.yuushilabs.esp32flasher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuushilabs.esp32flasher.data.NewlineMode
import com.yuushilabs.esp32flasher.ui.components.LogList
import com.yuushilabs.esp32flasher.usb.UsbSerialManager

private val BAUD_RATES = listOf(9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600)

@Composable
fun SerialMonitorScreen(
    viewModel: MonitorViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var baudMenuExpanded by remember { mutableStateOf(false) }

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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Terminal, contentDescription = null)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("シリアルモニター", style = MaterialTheme.typography.titleSmall)
                        Text(
                            when (val connection = state.connection) {
                                is UsbSerialManager.ConnectionState.Connected ->
                                    "${connection.deviceLabel} / ${state.baudRate} bps"

                                is UsbSerialManager.ConnectionState.Error -> connection.message
                                UsbSerialManager.ConnectionState.Disconnected ->
                                    "USB デバイス未接続"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.foundation.layout.Box {
                        OutlinedButton(
                            onClick = { baudMenuExpanded = true },
                            enabled = state.isConnected,
                        ) {
                            Text("${state.baudRate}")
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
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
                                        viewModel.setBaudRate(rate)
                                    },
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = viewModel::resetBoard,
                        enabled = state.isConnected,
                    ) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = "リセット")
                    }
                    IconButton(
                        onClick = viewModel::toggleTimestamps,
                        enabled = state.timestamps,
                    ) {
                        Icon(Icons.Filled.Schedule, contentDescription = "タイムスタンプ")
                    }
                    IconButton(onClick = viewModel::clear) {
                        Icon(Icons.Filled.Delete, contentDescription = "ログをクリア")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NewlineMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.newlineMode == mode,
                            onClick = { viewModel.setNewlineMode(mode) },
                            label = { Text(mode.label) },
                        )
                    }
                    FilterChip(
                        selected = state.autoScroll,
                        onClick = viewModel::toggleAutoScroll,
                        label = { Text("自動スクロール") },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("AT", "AT+GMR", "help").forEach { command ->
                        OutlinedButton(
                            onClick = { viewModel.sendQuickCommand(command) },
                            enabled = state.isConnected,
                        ) { Text(command) }
                    }
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
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
                    .fillMaxSize()
                    .padding(12.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.input,
                onValueChange = viewModel::setInput,
                modifier = Modifier.weight(1f),
                placeholder = { Text("送信するコマンド") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { viewModel.send() }),
            )
            Button(onClick = viewModel::send, enabled = state.isConnected) {
                Icon(Icons.Filled.Send, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("送信")
            }
        }
    }
}
