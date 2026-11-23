package com.yuushilabs.esp32flasher.usb

import com.yuushilabs.esp32flasher.core.EspBootloaderProtocol
import com.yuushilabs.esp32flasher.core.EspCommands
import com.yuushilabs.esp32flasher.core.EspProtocolException
import com.yuushilabs.esp32flasher.core.SlipCodec
import com.yuushilabs.esp32flasher.core.SlipDecoder
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EspFlasherEngineTest {
    private data class Request(val op: Int, val checksum: Long, val payload: ByteArray) {
        fun u32(offset: Int): Long {
            var value = 0L
            for (index in 0 until 4) {
                value = value or ((payload[offset + index].toLong() and 0xFFL) shl (8 * index))
            }
            return value
        }
    }

    private class FakeEspDevice(
        private val chipMagic: Long = 0x00F01D83L,
        private val macLow: Long = 0x1234ABCDL,
        private val macHigh: Long = 0x0000F00DL,
        private val failDeflatedBegin: Boolean = false,
        private val syncsToIgnore: Int = 0,
        private val alwaysSilent: Boolean = false,
    ) : SerialLink {
        override var baudRate: Int = 115_200
            private set

        val dtrRtsLog = mutableListOf<Pair<Boolean, Boolean>>()
        val commands = mutableListOf<Request>()
        val flashPayload = ByteArrayOutputStream()
        var lastFlashBegin: Request? = null
        var lastFlashEnd: Request? = null
        var syncRequests = 0
            private set

        private val decoder = SlipDecoder()
        private val pending = ArrayDeque<Byte>()

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

        override fun setDtrRts(dtr: Boolean, rts: Boolean) {
            dtrRtsLog += dtr to rts
        }

        override fun setBaudRate(baudRate: Int) {
            this.baudRate = baudRate
        }

        override fun flushInput() {
            pending.clear()
            decoder.reset()
        }

        private fun handleFrame(frame: ByteArray) {
            require(frame.size >= 8) { "request frame too short" }
            val op = frame[1].toInt() and 0xFF
            val size = (frame[2].toInt() and 0xFF) or ((frame[3].toInt() and 0xFF) shl 8)
            var checksum = 0L
            for (index in 0 until 4) {
                checksum = checksum or ((frame[4 + index].toLong() and 0xFFL) shl (8 * index))
            }
            val payload = frame.copyOfRange(8, 8 + size)
            val request = Request(op, checksum, payload)
            commands += request
            if (alwaysSilent) return
            when (op) {
                EspCommands.SYNC -> {
                    syncRequests++
                    if (syncRequests > syncsToIgnore) {
                        respond(op, 0, EspBootloaderProtocol.syncPayload())
                    }
                }

                EspCommands.READ_REG -> respond(op, registerValue(request.u32(0)), ByteArray(0))

                EspCommands.SPI_ATTACH, EspCommands.SPI_SET_PARAMS -> respond(op, 0, ByteArray(0))

                EspCommands.FLASH_BEGIN -> {
                    lastFlashBegin = request
                    respond(op, 0, ByteArray(0))
                }

                EspCommands.FLASH_DEFL_BEGIN -> {
                    if (failDeflatedBegin) {
                        respond(op, 1, ByteArray(0))
                    } else {
                        lastFlashBegin = request
                        respond(op, 0, ByteArray(0))
                    }
                }

                EspCommands.FLASH_DATA, EspCommands.FLASH_DEFL_DATA -> {
                    flashPayload.write(payload.copyOfRange(16, payload.size))
                    respond(op, 0, ByteArray(0))
                }

                EspCommands.FLASH_END, EspCommands.FLASH_DEFL_END -> {
                    lastFlashEnd = request
                    respond(op, 0, ByteArray(0))
                }

                else -> respond(op, 0, ByteArray(0))
            }
        }

        private fun registerValue(address: Long): Long = when (address) {
            EspFlasherEngine.CHIP_MAGIC_REGISTER -> chipMagic
            EspFlasherEngine.MAC_LOW_REGISTER -> macLow
            EspFlasherEngine.MAC_HIGH_REGISTER -> macHigh
            else -> 0L
        }

        private fun respond(op: Int, value: Long, data: ByteArray) {
            val packet = ByteArray(8 + data.size)
            packet[0] = 0x01
            packet[1] = op.toByte()
            packet[2] = (data.size and 0xFF).toByte()
            packet[3] = ((data.size shr 8) and 0xFF).toByte()
            for (index in 0 until 4) {
                packet[4 + index] = ((value shr (8 * index)) and 0xFF).toByte()
            }
            data.copyInto(packet, 8)
            SlipCodec.encode(packet).forEach { pending.addLast(it) }
        }
    }

    private fun engine(
        device: FakeEspDevice,
        config: FlashConfig = FlashConfig(autoReset = false, resetAfterFlash = false),
        listener: FlashListener = FlashListener.NONE,
        cancellation: () -> Boolean = { false },
    ) = EspFlasherEngine(
        link = device,
        config = config,
        listener = listener,
        sleeper = {},
        cancellation = cancellation,
    )

    private fun padded(image: ByteArray, offset: Long): ByteArray =
        EspBootloaderProtocol.padToEraseSize(image, offset)

    private fun inflate(data: ByteArray, size: Int): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArray(size)
        val read = inflater.inflate(out)
        inflater.end()
        return out.copyOf(read)
    }

    @Test
    fun `connect syncs and reads the chip and mac`() {
        val device = FakeEspDevice()
        val chip = engine(device).connect()
        assertEquals("ESP32", chip.name)
        assertEquals("cd:ab:34:12:0d:f0", chip.macAddress)
        assertEquals("ESP32 (cd:ab:34:12:0d:f0)", chip.displayName)
        assertTrue(device.syncRequests >= 1)
    }

    @Test
    fun `bootloader reset follows the esptool dtr rts sequence`() {
        val device = FakeEspDevice()
        engine(device).performBootloaderReset()
        assertEquals(
            listOf(false to true, true to false, false to false),
            device.dtrRtsLog,
        )
    }

    @Test
    fun `connect with auto reset drives dtr rts before sync`() {
        val device = FakeEspDevice()
        val engine = engine(device, FlashConfig(autoReset = true, resetAfterFlash = false))
        engine.connect()
        assertEquals(3, device.dtrRtsLog.size)
        assertEquals(1, device.syncRequests)
        assertTrue(device.commands.any { it.op == EspCommands.READ_REG })
    }

    @Test
    fun `sync is retried until the device answers`() {
        val device = FakeEspDevice(syncsToIgnore = 2)
        val config = FlashConfig(autoReset = false, syncAttempts = 5, syncTimeoutMs = 20)
        val chip = engine(device, config).connect()
        assertEquals("ESP32", chip.name)
        assertEquals(3, device.syncRequests)
    }

    @Test
    fun `compressed flash sends the deflated sequence`() {
        val device = FakeEspDevice()
        val image = ByteArray(5000) { (it % 251).toByte() }
        val outcome = engine(device).flash(image, 0x10000)
        assertTrue("unexpected outcome: $outcome", outcome is FlashOutcome.Success)
        val success = outcome as FlashOutcome.Success
        assertTrue(success.compressed)
        assertEquals(8192, success.uncompressedBytes)

        val begin = device.lastFlashBegin
        assertNotNull(begin)
        assertEquals(EspCommands.FLASH_DEFL_BEGIN, begin!!.op)
        assertEquals(8192L, begin.u32(0))
        assertEquals(0x10000L, begin.u32(12))

        val dataCommands = device.commands.filter { it.op == EspCommands.FLASH_DEFL_DATA }
        assertEquals(begin.u32(4).toInt(), dataCommands.size)
        dataCommands.forEachIndexed { index, command ->
            assertEquals(index.toLong(), command.u32(4))
            val chunk = command.payload.copyOfRange(16, command.payload.size)
            assertEquals(
                "checksum mismatch for block $index",
                EspBootloaderProtocol.checksum(chunk).toLong(),
                command.checksum,
            )
            assertTrue(chunk.size <= 1024)
        }
        assertEquals(EspCommands.FLASH_DEFL_END, device.lastFlashEnd?.op)
        assertEquals(0L, device.lastFlashEnd!!.u32(0))

        val restored = inflate(device.flashPayload.toByteArray(), 8192)
        assertArrayEquals(padded(image, 0x10000), restored)
    }

    @Test
    fun `uncompressed flash sends padded flash data`() {
        val device = FakeEspDevice()
        val image = ByteArray(3000) { (it % 97).toByte() }
        val outcome = engine(device, FlashConfig(autoReset = false, compression = false)).flash(image, 0x0)
        assertTrue(outcome is FlashOutcome.Success)
        assertFalse((outcome as FlashOutcome.Success).compressed)

        val begin = device.lastFlashBegin!!
        assertEquals(EspCommands.FLASH_BEGIN, begin.op)
        assertEquals(4096L, begin.u32(0))
        assertEquals(4L, begin.u32(4))
        assertEquals(1024L, begin.u32(8))
        assertEquals(0L, begin.u32(12))

        val dataCommands = device.commands.filter { it.op == EspCommands.FLASH_DATA }
        assertEquals(4, dataCommands.size)
        assertArrayEquals(padded(image, 0x0), device.flashPayload.toByteArray())
        assertEquals(EspCommands.FLASH_END, device.lastFlashEnd?.op)
    }

    @Test
    fun `compressed flash falls back to uncompressed when deflate is rejected`() {
        val device = FakeEspDevice(failDeflatedBegin = true)
        val image = ByteArray(2048) { 0x11 }
        val outcome = engine(device).flash(image, 0x10000)
        assertTrue("unexpected outcome: $outcome", outcome is FlashOutcome.Success)
        assertFalse((outcome as FlashOutcome.Success).compressed)
        assertTrue(device.commands.any { it.op == EspCommands.FLASH_DEFL_BEGIN })
        assertTrue(device.commands.any { it.op == EspCommands.FLASH_BEGIN })
        assertEquals(EspCommands.FLASH_END, device.lastFlashEnd?.op)
        assertArrayEquals(padded(image, 0x10000), device.flashPayload.toByteArray())
    }

    @Test
    fun `unaligned offsets pad to the enclosing sector`() {
        val device = FakeEspDevice()
        val image = ByteArray(10) { 0x7E }
        val outcome = engine(device, FlashConfig(autoReset = false, compression = false)).flash(image, 0x10002)
        assertTrue(outcome is FlashOutcome.Success)
        val payload = device.flashPayload.toByteArray()
        assertEquals(4096, payload.size)
        assertArrayEquals(image, payload.copyOfRange(0, image.size))
        assertTrue(payload.copyOfRange(image.size, payload.size).all { it == 0xFF.toByte() })
        assertEquals(4096L, device.lastFlashBegin!!.u32(0))
    }

    @Test
    fun `flash reports progress up to one hundred percent`() {
        val progressList = mutableListOf<FlashProgress>()
        val listener = object : FlashListener {
            override fun onProgress(progress: FlashProgress) {
                progressList += progress
            }
        }
        val device = FakeEspDevice()
        val outcome = engine(device, listener = listener).flash(ByteArray(2048) { 1 }, 0)
        assertTrue(outcome is FlashOutcome.Success)
        assertTrue(progressList.isNotEmpty())
        assertEquals(100, progressList.last().percent)
        assertTrue(progressList.last().bytesWritten > 0)
    }

    @Test
    fun `flash fails when the device never answers sync`() {
        val device = FakeEspDevice(alwaysSilent = true)
        val config = FlashConfig(autoReset = false, syncAttempts = 2, syncTimeoutMs = 10)
        val outcome = engine(device, config).flash(ByteArray(64) { 1 }, 0)
        assertTrue("unexpected outcome: $outcome", outcome is FlashOutcome.Failure)
        assertTrue((outcome as FlashOutcome.Failure).message.contains("同期"))
    }

    @Test
    fun `connect throws when sync never succeeds`() {
        val device = FakeEspDevice(alwaysSilent = true)
        val config = FlashConfig(autoReset = false, syncAttempts = 1, syncTimeoutMs = 10)
        assertThrows(EspProtocolException::class.java) { engine(device, config).connect() }
    }

    @Test
    fun `cancellation stops the flash`() {
        val device = FakeEspDevice()
        val outcome = engine(device, cancellation = { true }).flash(ByteArray(64) { 1 }, 0)
        assertEquals(FlashOutcome.Cancelled, outcome)
    }

    @Test
    fun `empty images are rejected`() {
        val device = FakeEspDevice()
        val outcome = engine(device).flash(ByteArray(0), 0)
        assertTrue(outcome is FlashOutcome.Failure)
    }

    @Test
    fun `hard reset pulses the enable line`() {
        val device = FakeEspDevice()
        val engine = engine(device)
        engine.connect()
        device.dtrRtsLog.clear()
        engine.hardReset()
        assertEquals(listOf(false to true, false to false), device.dtrRtsLog)
        assertFalse(engine.isSynced)
    }

    @Test
    fun `baud rate change is negotiated with the bootloader`() {
        val device = FakeEspDevice()
        val engine = engine(device)
        engine.connect()
        assertTrue(engine.changeBaudRate(460_800))
        assertEquals(460_800, device.baudRate)
        val change = device.commands.first { it.op == EspCommands.CHANGE_BAUDRATE }
        assertEquals(460_800L, change.u32(0))
        assertEquals(115_200L, change.u32(4))
    }

    @Test
    fun `baud rate change keeps the old rate when the device stays silent`() {
        val device = FakeEspDevice(alwaysSilent = true)
        val engine = engine(device, FlashConfig(autoReset = false, responseTimeoutMs = 10))
        assertFalse(engine.changeBaudRate(460_800, 115_200))
        assertEquals(115_200, device.baudRate)
    }
}
