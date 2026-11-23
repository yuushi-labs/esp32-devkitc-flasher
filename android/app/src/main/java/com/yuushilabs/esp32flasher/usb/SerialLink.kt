package com.yuushilabs.esp32flasher.usb

/**
 * Minimal byte-level serial port abstraction. The Android implementation wraps
 * `usb-serial-for-android`; JVM tests use an in-memory ESP32 simulator.
 */
interface SerialLink {
    val baudRate: Int

    fun write(data: ByteArray)

    /** Blocking read. Returns the number of bytes read, or a value <= 0 when nothing arrived. */
    fun read(buffer: ByteArray, timeoutMs: Int): Int

    fun setDtrRts(dtr: Boolean, rts: Boolean)

    fun setBaudRate(baudRate: Int)

    fun flushInput()
}
