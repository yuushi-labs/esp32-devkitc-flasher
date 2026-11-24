package com.yuushilabs.esp32flasher.usb

import com.yuushilabs.esp32flasher.core.EspBootloaderProtocol
import com.yuushilabs.esp32flasher.core.EspCommands
import com.yuushilabs.esp32flasher.core.EspProtocolException
import com.yuushilabs.esp32flasher.core.EspStub
import com.yuushilabs.esp32flasher.core.EspStubParser
import com.yuushilabs.esp32flasher.core.SlipCodec
import com.yuushilabs.esp32flasher.core.SlipDecoder
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol-level checks for the ESP32 flash reader.
 *
 * The fake link below behaves like esptool's own model of the two read paths: the ROM answers
 * `READ_FLASH_SLOW` with 64 byte blocks, and the stub answers `READ_FLASH` with 0x1000-byte SLIP
 * frames that must be acknowledged with the running byte count and that end with an MD5 digest.
 * Real hardware still has to confirm it, but a wrong frame layout or a missing acknowledgement
 * fails here.
 */
class EspFlasherEngineReadTest {
    private data class Request(val op: Int, val payload: ByteArray) {
        fun u32(offset: Int): Long {
            var value = 0L
            for (index in 0 until 4) {
                value = value or ((payload[offset + index].toLong() and 0xFFL) shl (8 * index))
            }
            return value
        }
    }

    private class FakeFlashDevice(
        private val flash: ByteArray,
        private val stubAvailable: Boolean = true,
        private val corruptDigest: Boolean = false,
        private val rejectSlowReads: Boolean = false,
        private val slowBlockSize: Int = 64,
    ) : SerialLink {
        override var baudRate: Int = EspFlasherEngine.INITIAL_SYNC_BAUD
            private set

        val commands = mutableListOf<Int>()
        val memSegments = LinkedHashMap<Long, ByteArray>()
        var stubStarted = false
            private set

        private val decoder = SlipDecoder()
        private val pending = ArrayDeque<Byte>()
        private var ramAddress = 0L
        private var ramBuffer = ByteArray(0)
        private var ramSize = 0
        private var ramBlocks = 0

        private var readStart = 0L
        private var readOffset = 0L
        private var readRemaining = 0
        private var readTotal = 0
        private var readReceived = 0
        private var readActive = false

        override fun write(data: ByteArray) {
            decoder.feed(data).forEach(::handleFrame)
        }

        override fun read(buffer: ByteArray, timeoutMs: Int): Int {
            var count = 0
            while (pending.isNotEmpty() && count < buffer.size) {
                buffer[count++] = pending.removeFirst()
            }
            return count
        }

        override fun setDtrRts(dtr: Boolean, rts: Boolean) = Unit

        override fun setBaudRate(baudRate: Int) {
            this.baudRate = baudRate
        }

        fun switchRomBaud(rate: Int) {
            this.baudRate = rate
        }

        override fun flushInput() {
            pending.clear()
            decoder.reset()
        }

        private fun handleFrame(frame: ByteArray) {
            if (frame.size == 4) {
                // Acknowledgement of a stub read frame: the running total of received bytes.
                if (!readActive) return
                val ack = (frame[0].toLong() and 0xFF) or
                    ((frame[1].toLong() and 0xFF) shl 8) or
                    ((frame[2].toLong() and 0xFF) shl 16) or
                    ((frame[3].toLong() and 0xFF) shl 24)
                if (ack.toInt() != readReceived) return
                if (readReceived < readTotal) {
                    sendReadFrame()
                } else {
                    val digest = MessageDigest.getInstance("MD5")
                        .digest(flash.copyOfRange(readStart.toInt(), readStart.toInt() + readTotal))
                    if (corruptDigest) digest[0] = (digest[0].toInt() xor 0xFF).toByte()
                    queue(digest)
                    readActive = false
                }
                return
            }
            if (frame.size < 8) return
            val op = frame[1].toInt() and 0xFF
            val size = (frame[2].toInt() and 0xFF) or ((frame[3].toInt() and 0xFF) shl 8)
            val payload = frame.copyOfRange(8, 8 + size)
            val request = Request(op, payload)
            commands += op
            when (op) {
                EspCommands.SYNC -> repeat(8) { respond(op) }
                EspCommands.READ_REG -> respond(op, value = 0x00F01D83L)
                EspCommands.CHANGE_BAUDRATE -> {
                    respond(op)
                    baudRate = request.u32(0).toInt()
                }
                EspCommands.MEM_BEGIN -> {
                    ramAddress = request.u32(12)
                    ramSize = request.u32(0).toInt()
                    ramBlocks = request.u32(4).toInt()
                    ramBuffer = ByteArray(ramSize)
                    respond(op)
                }
                EspCommands.MEM_DATA -> {
                    val chunk = payload.copyOfRange(16, payload.size)
                    val sequence = request.u32(4).toInt()
                    val from = sequence * EspBootloaderProtocol.RAM_BLOCK_SIZE
                    chunk.copyInto(ramBuffer, from)
                    if (sequence + 1 >= ramBlocks) {
                        memSegments[ramAddress] = ramBuffer.copyOf()
                    }
                    respond(op)
                }
                EspCommands.MEM_END -> {
                    respond(op)
                    if (stubAvailable) {
                        stubStarted = true
                        queue("OHAI".toByteArray(Charsets.US_ASCII))
                    }
                }
                EspCommands.SPI_ATTACH, EspCommands.SPI_SET_PARAMS -> respond(op)
                EspCommands.READ_FLASH -> {
                    readStart = request.u32(0)
                    readOffset = readStart
                    readTotal = request.u32(4).toInt()
                    readRemaining = readTotal
                    readReceived = 0
                    if (!stubStarted) {
                        respond(op, status = 1, errorCode = 1)
                    } else {
                        readActive = true
                        sendReadFrame()
                    }
                }
                EspCommands.READ_FLASH_SLOW -> {
                    val offset = request.u32(0)
                    val length = request.u32(4).toInt()
                    // The ROM returns a full block, then its status bytes.
                    val data = flash.copyOfRange(offset.toInt(), offset.toInt() + slowBlockSize)
                    val status = if (rejectSlowReads || length > slowBlockSize) 1 else 0
                    respondWithPayload(op, data + byteArrayOf(status.toByte(), 0, 0, 0))
                }
                else -> respond(op)
            }
        }

        private fun sendReadFrame() {
            val chunk = minOf(0x1000, readRemaining, flash.size - readOffset.toInt())
            val frame = flash.copyOfRange(readOffset.toInt(), readOffset.toInt() + chunk)
            queue(frame)
            readOffset += chunk
            readRemaining -= chunk
            readReceived += chunk
        }

        private fun queue(payload: ByteArray) {
            SlipCodec.encode(payload).forEach { pending.addLast(it) }
        }

        private fun respond(
            op: Int,
            status: Int = 0,
            errorCode: Int = 0,
            value: Long = 0,
            data: ByteArray = ByteArray(0),
        ) = respondWithPayload(op, data + byteArrayOf(status.toByte(), errorCode.toByte(), 0, 0), value)

        private fun respondWithPayload(op: Int, payload: ByteArray, value: Long = 0) {
            val packet = ByteArray(8 + payload.size)
            packet[0] = 0x01
            packet[1] = op.toByte()
            packet[2] = (payload.size and 0xFF).toByte()
            packet[3] = ((payload.size shr 8) and 0xFF).toByte()
            for (index in 0 until 4) {
                packet[4 + index] = ((value shr (8 * index)) and 0xFF).toByte()
            }
            payload.copyInto(packet, 8)
            queue(packet)
        }
    }

    private fun engine(device: SerialLink, listener: FlashListener = FlashListener.NONE) =
        EspFlasherEngine(
            link = device,
            config = FlashConfig(autoReset = false, resetAfterFlash = false),
            listener = listener,
            sleeper = {},
        )

    private fun stub(): EspStub {
        val stream = javaClass.classLoader!!.getResourceAsStream("esp32-stub-flasher.json")
            ?: throw AssertionError("スタブのテストリソースがありません")
        return EspStubParser.parse(stream.use { it.readBytes() }.toString(Charsets.UTF_8))
    }

    private fun flashImage(size: Int = 0x10000): ByteArray = ByteArray(size) { (it * 13 % 251).toByte() }

    @Test
    fun `reads a partition through the stub with the md5 check`() {
        val flash = flashImage()
        val device = FakeFlashDevice(flash)
        val engine = engine(device)

        val data = engine.readFlash(0x8000L, 0x1000, stub())

        assertArrayEquals(flash.copyOfRange(0x8000, 0x9000), data)
        assertTrue(device.stubStarted)
        val memBegin = device.commands.indexOf(EspCommands.MEM_BEGIN)
        assertTrue("スタブの RAM 書き込みがありません", memBegin > 0)
        assertTrue(device.commands.contains(EspCommands.READ_FLASH))
        assertFalse(device.commands.contains(EspCommands.READ_FLASH_SLOW))
        // The stub's two segments (text and data) were both copied into RAM.
        assertEquals(2, device.memSegments.size)
        assertTrue(device.memSegments.values.all { it.isNotEmpty() })
    }

    @Test
    fun `uploads the real stub segments at their addresses`() {
        val stub = stub()
        val device = FakeFlashDevice(flashImage())
        engine(device).readFlash(0L, 0x1000, stub)

        assertTrue(stub.segments.size >= 2)
        for (segment in stub.segments) {
            val uploaded = device.memSegments[segment.address]
            assertNotNull("アドレス 0x%X のセグメントがありません".format(segment.address), uploaded)
            assertArrayEquals(segment.data, uploaded)
        }
        assertEquals(stub.totalBytes, stub.segments.sumOf { it.data.size })
        assertTrue(stub.entry > 0x40000000L)
    }

    @Test
    fun `reads larger regions in acknowledged chunks`() {
        val flash = flashImage(0x40000)
        val device = FakeFlashDevice(flash)
        val data = engine(device).readFlash(0x10000L, 3 * 0x10000, stub())

        assertArrayEquals(flash.copyOfRange(0x10000, 0x40000), data)
        // 64 KB chunks: three READ_FLASH commands for 192 KB.
        assertEquals(3, device.commands.count { it == EspCommands.READ_FLASH })
    }

    @Test
    fun `falls back to the rom slow read when the stub cannot start`() {
        val flash = flashImage()
        val device = FakeFlashDevice(flash, stubAvailable = false)
        val listener = RecordingListener()
        val data = engine(device, listener).readFlash(0x8000L, 0x100, stub())

        assertArrayEquals(flash.copyOfRange(0x8000, 0x8100), data)
        assertTrue(device.commands.contains(EspCommands.READ_FLASH_SLOW))
        assertTrue(listener.logs.any { it.contains("READ_FLASH_SLOW") })
    }

    @Test
    fun `reads without a stub by using the rom command only`() {
        val flash = flashImage()
        val device = FakeFlashDevice(flash)
        val data = engine(device).readFlash(0x100L, 0x80, stub = null)

        assertArrayEquals(flash.copyOfRange(0x100, 0x180), data)
        assertFalse(device.commands.contains(EspCommands.MEM_BEGIN))
        assertTrue(device.commands.contains(EspCommands.READ_FLASH_SLOW))
    }

    @Test
    fun `a corrupted stub digest fails the read`() {
        val device = FakeFlashDevice(flashImage(), corruptDigest = true)
        assertThrows(EspProtocolException::class.java) {
            engine(device).readFlash(0x0L, 0x1000, stub())
        }
    }

    @Test
    fun `a rejected slow read is reported`() {
        val device = FakeFlashDevice(flashImage(), stubAvailable = false, rejectSlowReads = true)
        assertThrows(EspProtocolException::class.java) {
            engine(device).readFlash(0x0L, 0x100, stub())
        }
    }

    @Test
    fun `progress is reported while reading`() {
        val flash = flashImage(0x20000)
        val device = FakeFlashDevice(flash)
        val listener = RecordingListener()
        engine(device, listener).readFlash(0x0L, 0x20000, stub())

        assertTrue(listener.progress.isNotEmpty())
        assertEquals(0x20000L, listener.progress.last().totalBytes)
        assertEquals(0x20000L, listener.progress.last().bytesWritten)
    }

    @Test
    fun `stub parser rejects broken json`() {
        assertThrows(Exception::class.java) { EspStubParser.parse("{}") }
        assertThrows(Exception::class.java) { EspStubParser.parse("not json") }
    }

    private class RecordingListener : FlashListener {
        val logs = mutableListOf<String>()
        val progress = mutableListOf<FlashProgress>()

        override fun onLog(message: String) {
            logs += message
        }

        override fun onProgress(progress: FlashProgress) {
            this.progress += progress
        }
    }
}
