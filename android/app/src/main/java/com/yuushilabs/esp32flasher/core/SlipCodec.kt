package com.yuushilabs.esp32flasher.core

import java.io.ByteArrayOutputStream

/**
 * SLIP (RFC 1055) framing used by the ESP32 ROM bootloader.
 *
 * Frames are delimited by [END]; [ESC] escapes [END] and [ESC] inside the payload.
 */
object SlipCodec {
    const val END = 0xC0
    const val ESC = 0xDB
    const val ESC_END = 0xDC
    const val ESC_ESC = 0xDD
    const val MAX_FRAME_SIZE = 64 * 1024

    fun encode(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 8)
        out.write(END)
        for (byte in payload) {
            when (val value = byte.toInt() and 0xFF) {
                END -> {
                    out.write(ESC)
                    out.write(ESC_END)
                }

                ESC -> {
                    out.write(ESC)
                    out.write(ESC_ESC)
                }

                else -> out.write(value)
            }
        }
        out.write(END)
        return out.toByteArray()
    }

    /** Decodes a single frame, throwing [SlipFormatException] when it is malformed. */
    fun decode(frame: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(frame.size)
        var index = 0
        while (index < frame.size) {
            val value = frame[index].toInt() and 0xFF
            when (value) {
                END -> index++
                ESC -> {
                    require(index + 1 < frame.size) { "SLIP エスケープが途中で終わっています" }
                    when (val escaped = frame[index + 1].toInt() and 0xFF) {
                        ESC_END -> out.write(END)
                        ESC_ESC -> out.write(ESC)
                        else -> throw SlipFormatException(
                            "不正な SLIP エスケープシーケンスです: 0x%02X".format(escaped),
                        )
                    }
                    index += 2
                }

                else -> {
                    out.write(value)
                    index++
                }
            }
        }
        return out.toByteArray()
    }

    fun checksum(data: ByteArray): Int = data.sumOf { it.toInt() and 0xFF } and 0xFF
}

class SlipFormatException(message: String) : Exception(message)

/**
 * Incremental SLIP decoder. Bytes are pushed in as they arrive from the serial port and every
 * complete frame is emitted. Malformed frames are dropped so a noisy line cannot wedge the
 * decoder.
 */
class SlipDecoder(private val maxFrameSize: Int = SlipCodec.MAX_FRAME_SIZE) {
    private val buffer = ByteArrayOutputStream()
    private var escaping = false
    private var overflowed = false

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var index = offset
        val end = offset + length
        while (index < end) {
            val value = bytes[index].toInt() and 0xFF
            index++
            when {
                value == SlipCodec.END -> {
                    if (buffer.size() > 0 && !overflowed) {
                        frames += buffer.toByteArray()
                    }
                    buffer.reset()
                    escaping = false
                    overflowed = false
                }

                overflowed -> Unit

                value == SlipCodec.ESC -> {
                    if (escaping) {
                        overflowed = true
                    }
                    escaping = true
                }

                escaping -> {
                    escaping = false
                    when (value) {
                        SlipCodec.ESC_END -> append(SlipCodec.END)
                        SlipCodec.ESC_ESC -> append(SlipCodec.ESC)
                        else -> overflowed = true
                    }
                }

                else -> append(value)
            }
        }
        return frames
    }

    fun reset() {
        buffer.reset()
        escaping = false
        overflowed = false
    }

    val pendingBytes: Int get() = buffer.size()

    private fun append(value: Int) {
        if (buffer.size() >= maxFrameSize) {
            overflowed = true
            buffer.reset()
            return
        }
        buffer.write(value)
    }
}
