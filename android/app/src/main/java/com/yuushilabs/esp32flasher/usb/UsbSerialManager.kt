package com.yuushilabs.esp32flasher.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Owns the USB OTG connection: enumerates attached USB-UART bridges, asks for the runtime USB
 * permission, opens the port and pumps received bytes to the serial monitor.
 */
class UsbSerialManager(private val context: Context) {
    data class DeviceEntry(
        val device: UsbDevice,
        val label: String,
        val driverName: String?,
        val isSupported: Boolean,
        val hasPermission: Boolean,
    )

    sealed interface ConnectionState {
        data object Disconnected : ConnectionState

        data class Connected(val deviceLabel: String, val baudRate: Int) : ConnectionState

        data class Error(val message: String) : ConnectionState
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val prober = UsbSerialProber.getDefaultProber()

    private val _devices = MutableStateFlow<List<DeviceEntry>>(emptyList())
    val devices: StateFlow<List<DeviceEntry>> = _devices.asStateFlow()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _received = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    val received: SharedFlow<ByteArray> = _received.asSharedFlow()

    @Volatile
    private var transport: UsbSerialTransport? = null

    @Volatile
    private var monitoring = false

    /** True while a flash owns the port, so the monitor pump and reset pulses stay out of it. */
    @Volatile
    private var exclusiveAccess = false

    @Volatile
    private var lastMonitorBaud: Int? = null

    private var monitorThread: Thread? = null

    val link: SerialLink? get() = transport

    val isConnected: Boolean get() = transport?.isOpen == true

    val connectedBaudRate: Int get() = transport?.baudRate ?: 0

    fun refreshDevices() {
        val entries = usbManager.deviceList.values.map { device ->
            val driver = runCatching { prober.probeDevice(device) }.getOrNull()
            DeviceEntry(
                device = device,
                label = describe(device, driver),
                driverName = driver?.javaClass?.simpleName?.removeSuffix("SerialDriver"),
                isSupported = driver != null,
                hasPermission = usbManager.hasPermission(device),
            )
        }.sortedWith(compareByDescending<DeviceEntry> { it.isSupported }.thenBy { it.label })
        _devices.value = entries
    }

    suspend fun requestPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        return suspendCancellableCoroutine { continuation ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    val target = intent.usbDevice()
                    if (target != null && target.deviceId != device.deviceId) return
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    runCatching { receiverContext.unregisterReceiver(this) }
                    refreshDevices()
                    if (continuation.isActive) continuation.resume(granted)
                }
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(ACTION_USB_PERMISSION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            continuation.invokeOnCancellation {
                runCatching { context.unregisterReceiver(receiver) }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                flags,
            )
            usbManager.requestPermission(device, pendingIntent)
        }
    }

    fun open(device: UsbDevice, baudRate: Int): Result<UsbSerialTransport> {
        close()
        val driver = runCatching { prober.probeDevice(device) }.getOrNull()
            ?: return Result.failure(IOException("未対応の USB シリアルデバイスです"))
        if (!usbManager.hasPermission(device)) {
            return Result.failure(IOException("USB デバイスへのアクセス権がありません"))
        }
        val port = driver.ports.firstOrNull()
            ?: return Result.failure(IOException("シリアルポートが見つかりません"))
        val connection = usbManager.openDevice(device)
            ?: return Result.failure(IOException("USB デバイスを開けませんでした"))
        return try {
            port.open(connection)
            val opened = UsbSerialTransport(port, describe(device, driver))
            opened.setBaudRate(baudRate)
            transport = opened
            _state.value = ConnectionState.Connected(opened.deviceLabel, baudRate)
            refreshDevices()
            Result.success(opened)
        } catch (error: Exception) {
            runCatching { port.close() }
            runCatching { connection.close() }
            _state.value = ConnectionState.Error(error.message ?: "接続に失敗しました")
            Result.failure(error)
        }
    }

    fun close() {
        stopMonitoring()
        transport?.close()
        transport = null
        _state.value = ConnectionState.Disconnected
        refreshDevices()
    }

    fun startMonitoring(baudRate: Int? = null) {
        if (exclusiveAccess) return
        val current = transport ?: return
        if (monitoring) return
        // The port may have been opened at the flash baud rate; the monitor has its own.
        if (baudRate != null && baudRate > 0 && baudRate != current.baudRate) {
            runCatching { current.setBaudRate(baudRate) }
                .onSuccess { _state.value = ConnectionState.Connected(current.deviceLabel, baudRate) }
        }
        if (baudRate != null && baudRate > 0) lastMonitorBaud = baudRate
        monitoring = true
        monitorThread = Thread({
            val buffer = ByteArray(4096)
            while (monitoring) {
                val count = try {
                    current.read(buffer, MONITOR_READ_TIMEOUT_MS)
                } catch (error: IOException) {
                    break
                }
                if (count > 0) {
                    _received.tryEmit(buffer.copyOf(count))
                }
            }
        }, "esp32-serial-monitor").apply {
            isDaemon = true
            start()
        }
    }

    fun stopMonitoring() {
        monitoring = false
        monitorThread?.let { thread ->
            runCatching { thread.join(MONITOR_JOIN_TIMEOUT_MS) }
        }
        monitorThread = null
    }

    fun write(data: ByteArray): Result<Unit> {
        if (exclusiveAccess) {
            return Result.failure(IOException("書き込み中はシリアルモニターから送信できません"))
        }
        val current = transport ?: return Result.failure(IOException("シリアルポートが接続されていません"))
        return runCatching { current.write(data) }
    }

    fun setBaudRate(baudRate: Int): Result<Unit> {
        val current = transport ?: return Result.failure(IOException("シリアルポートが接続されていません"))
        return runCatching {
            current.setBaudRate(baudRate)
            _state.value = ConnectionState.Connected(current.deviceLabel, baudRate)
        }
    }

    fun setDtrRts(dtr: Boolean, rts: Boolean) {
        transport?.setDtrRts(dtr, rts)
    }

    /** Pulses EN low to reset the ESP32 without entering the bootloader. */
    fun pulseReset() {
        if (exclusiveAccess) return
        val current = transport ?: return
        current.setDtrRts(false, true)
        runCatching { Thread.sleep(RESET_PULSE_MS) }
        current.setDtrRts(false, false)
    }

    /**
     * Takes the port away from the monitor pump for a flash. Returns whether the monitor was
     * running, so [endExclusiveAccess] can put it back exactly as it was.
     */
    fun beginExclusiveAccess(baudRate: Int): Boolean {
        if (exclusiveAccess) return false
        exclusiveAccess = true
        if (baudRate > 0) lastMonitorBaud = baudRate
        val wasMonitoring = monitoring
        if (wasMonitoring) stopMonitoring()
        return wasMonitoring
    }

    fun endExclusiveAccess(resumeMonitoring: Boolean) {
        exclusiveAccess = false
        // The flash leaves the bridge at its upload rate; the monitor expects its own so the
        // first lines after a flash are not garbled (and so the next connect starts at 115200).
        val restoreBaud = lastMonitorBaud
        val current = transport
        if (current != null && restoreBaud != null && restoreBaud > 0 && current.baudRate != restoreBaud) {
            runCatching { current.setBaudRate(restoreBaud) }
                .onSuccess { _state.value = ConnectionState.Connected(current.deviceLabel, restoreBaud) }
        }
        if (resumeMonitoring && !monitoring) startMonitoring(restoreBaud)
    }

    private fun describe(device: UsbDevice, driver: UsbSerialDriver?): String {
        val driverName = driver?.javaClass?.simpleName?.removeSuffix("SerialDriver")
        val ids = "%04X:%04X".format(device.vendorId, device.productId)
        val name = driverName ?: (device.productName ?: device.deviceName)
        return "$name ($ids)"
    }

    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        }

    companion object {
        const val ACTION_USB_PERMISSION = "com.yuushilabs.esp32flasher.USB_PERMISSION"
        private const val MONITOR_READ_TIMEOUT_MS = 100
        private const val MONITOR_JOIN_TIMEOUT_MS = 500L
        private const val RESET_PULSE_MS = 100L
    }
}
