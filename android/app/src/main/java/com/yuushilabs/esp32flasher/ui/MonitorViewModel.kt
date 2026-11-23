package com.yuushilabs.esp32flasher.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yuushilabs.esp32flasher.AppContainer
import com.yuushilabs.esp32flasher.data.AppSettings
import com.yuushilabs.esp32flasher.data.NewlineMode
import com.yuushilabs.esp32flasher.usb.UsbSerialManager
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MonitorLine(
    val time: String,
    val text: String,
    val level: LogLevel = LogLevel.INFO,
)

data class MonitorUiState(
    val lines: List<MonitorLine> = emptyList(),
    val input: String = "",
    val baudRate: Int = AppSettings.DEFAULT_MONITOR_BAUD,
    val newlineMode: NewlineMode = NewlineMode.LF,
    val timestamps: Boolean = true,
    val autoScroll: Boolean = true,
    val connection: UsbSerialManager.ConnectionState = UsbSerialManager.ConnectionState.Disconnected,
    val message: String? = null,
) {
    val isConnected: Boolean get() = connection is UsbSerialManager.ConnectionState.Connected
}

/** Turns the raw byte stream of the shared USB port into monitor lines and sends commands. */
class MonitorViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(MonitorUiState())
    val state: StateFlow<MonitorUiState> = _state.asStateFlow()

    private val partialLine = StringBuilder()
    private var monitoring = false

    /** Keeps a multi-byte UTF-8 character that was split across USB read boundaries. */
    private val utf8Decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private val decodeBuffer = CharBuffer.allocate(16 * 1024)

    init {
        viewModelScope.launch {
            container.usbManager.received.collect(::handleBytes)
        }
        viewModelScope.launch {
            container.usbManager.state.collect { connection ->
                _state.update { it.copy(connection = connection) }
            }
        }
        viewModelScope.launch {
            container.settingsRepository.settings.collect { settings ->
                _state.update {
                    it.copy(
                        baudRate = settings.monitorBaudRate,
                        newlineMode = settings.newlineMode,
                        timestamps = settings.monitorTimestamps,
                    )
                }
            }
        }
    }

    fun startMonitoring() {
        monitoring = true
        // The port may still be at the flash baud rate; the monitor has its own.
        container.usbManager.startMonitoring(_state.value.baudRate)
    }

    fun stopMonitoring() {
        monitoring = false
        container.usbManager.stopMonitoring()
    }

    fun setInput(text: String) {
        _state.update { it.copy(input = text) }
    }

    fun send() {
        val text = _state.value.input
        if (text.isBlank()) return
        val payload = text.toByteArray() + _state.value.newlineMode.suffix
        val result = container.usbManager.write(payload)
        result.onSuccess {
            appendLocal("> $text", LogLevel.SUCCESS)
            _state.update { it.copy(input = "") }
        }.onFailure {
            appendLocal("送信に失敗しました: ${it.message}", LogLevel.ERROR)
            _state.update { it.copy(message = it.message ?: "送信に失敗しました") }
        }
    }

    fun sendQuickCommand(command: String) {
        val payload = command.toByteArray() + _state.value.newlineMode.suffix
        container.usbManager.write(payload)
            .onSuccess { appendLocal("> $command", LogLevel.SUCCESS) }
            .onFailure { appendLocal("送信に失敗しました: ${it.message}", LogLevel.ERROR) }
    }

    fun setBaudRate(baudRate: Int) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { container.usbManager.setBaudRate(baudRate) }
            result.onSuccess {
                _state.update { it.copy(baudRate = baudRate) }
                appendLocal("ボーレートを $baudRate bps に変更しました", LogLevel.SUCCESS)
                container.settingsRepository.setMonitorBaudRate(baudRate)
            }.onFailure {
                _state.update { it.copy(message = it.message ?: "ボーレート変更に失敗しました") }
            }
        }
    }

    fun setNewlineMode(mode: NewlineMode) {
        _state.update { it.copy(newlineMode = mode) }
        viewModelScope.launch { container.settingsRepository.setNewlineMode(mode) }
    }

    fun toggleTimestamps() {
        val enabled = !_state.value.timestamps
        _state.update { it.copy(timestamps = enabled) }
        viewModelScope.launch { container.settingsRepository.setMonitorTimestamps(enabled) }
    }

    fun toggleAutoScroll() {
        _state.update { it.copy(autoScroll = !it.autoScroll) }
    }

    fun clear() {
        partialLine.setLength(0)
        utf8Decoder.reset()
        _state.update { it.copy(lines = emptyList()) }
    }

    fun resetBoard() {
        viewModelScope.launch(Dispatchers.IO) {
            container.usbManager.pulseReset()
            appendLocal("EN をパルスしてリセットしました", LogLevel.SUCCESS)
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun handleBytes(bytes: ByteArray) {
        val text = decode(bytes)
        if (text.isEmpty()) return
        partialLine.append(text)
        var newlineIndex = partialLine.indexOf("\n")
        val completed = mutableListOf<String>()
        while (newlineIndex >= 0) {
            completed += partialLine.substring(0, newlineIndex).trimEnd('\r')
            partialLine.delete(0, newlineIndex + 1)
            newlineIndex = partialLine.indexOf("\n")
        }
        if (partialLine.length > MAX_PARTIAL_LINE) {
            completed += partialLine.toString()
            partialLine.setLength(0)
        }
        if (completed.isEmpty()) return
        val timestamp = now()
        _state.update { current ->
            val lines = current.lines + completed.map { MonitorLine(timestamp, it) }
            current.copy(lines = lines.takeLast(MAX_MONITOR_LINES))
        }
    }

    /** Decodes [bytes] while keeping an incomplete trailing UTF-8 sequence for the next chunk. */
    private fun decode(bytes: ByteArray): String {
        val input = ByteBuffer.wrap(bytes)
        val text = StringBuilder()
        while (true) {
            decodeBuffer.clear()
            val result = utf8Decoder.decode(input, decodeBuffer, false)
            decodeBuffer.flip()
            text.append(decodeBuffer)
            if (!result.isOverflow) break
        }
        return text.toString()
    }

    private fun appendLocal(text: String, level: LogLevel) {
        _state.update { current ->
            current.copy(
                lines = (current.lines + MonitorLine(now(), text, level)).takeLast(MAX_MONITOR_LINES),
            )
        }
    }

    private fun now(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())

    private companion object {
        const val MAX_MONITOR_LINES = 2_000
        const val MAX_PARTIAL_LINE = 4_096
    }
}
