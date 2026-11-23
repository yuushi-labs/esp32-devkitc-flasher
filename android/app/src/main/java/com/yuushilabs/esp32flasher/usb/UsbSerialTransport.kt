package com.yuushilabs.esp32flasher.usb

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException

/**
 * [SerialLink] backed by `usb-serial-for-android`.
 *
 * Writes are serialised because the flasher and the serial monitor share one port; reads use the
 * driver timeout so a blocked read cannot stall the UI thread forever.
 */
class UsbSerialTransport(
    private val port: UsbSerialPort,
    val deviceLabel: String,
) : SerialLink {
    private val writeLock = Any()

    @Volatile
    override var baudRate: Int = DEFAULT_BAUD_RATE
        private set

    val isOpen: Boolean get() = port.isOpen

    override fun write(data: ByteArray) {
        if (!port.isOpen) throw IOException("シリアルポートが開かれていません")
        synchronized(writeLock) {
            port.write(data, WRITE_TIMEOUT_MS)
        }
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        if (!port.isOpen) return -1
        return try {
            port.read(buffer, timeoutMs)
        } catch (timeout: com.hoho.android.usbserial.driver.SerialTimeoutException) {
            0
        }
    }

    override fun setDtrRts(dtr: Boolean, rts: Boolean) {
        if (!port.isOpen) return
        runCatching { port.setDTR(dtr) }
        runCatching { port.setRTS(rts) }
    }

    override fun setBaudRate(baudRate: Int) {
        if (!port.isOpen) {
            this.baudRate = baudRate
            return
        }
        port.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        this.baudRate = baudRate
    }

    override fun flushInput() {
        if (!port.isOpen) return
        runCatching { port.purgeHwBuffers(true, true) }
    }

    fun close() {
        runCatching { port.close() }
    }

    companion object {
        const val DEFAULT_BAUD_RATE = 115200
        private const val WRITE_TIMEOUT_MS = 2_000
    }
}
