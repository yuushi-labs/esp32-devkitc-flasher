package com.yuushilabs.esp32flasher.usb

import com.yuushilabs.esp32flasher.core.EspBootloaderProtocol
import com.yuushilabs.esp32flasher.core.EspCommands
import com.yuushilabs.esp32flasher.core.EspProtocolException
import com.yuushilabs.esp32flasher.core.SlipCodec
import com.yuushilabs.esp32flasher.core.SlipDecoder
import java.io.ByteArrayOutputStream
import java.io.IOException
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
        private val syncResponses: Int = 8,
        private val emitNoiseBeforeReplies: Boolean = false,
        private val rejectDataCommands: Boolean = false,
        private val alwaysSilent: Boolean = false,
        private val rejectBaudChange: Boolean = false,
        private val ignoreBaudChange: Boolean = false,
        private val failSwitchToBaud: Int? = null,
        private val silentAtBaud: Int? = null,
        initialBaudRate: Int = 115_200,
    ) : SerialLink {
        override var baudRate: Int = initialBaudRate
            private set

        val dtrRtsLog = mutableListOf<Pair<Boolean, Boolean>>()
        val commands = mutableListOf<Request>()

        /** Link rate the USB bridge had when each entry of [commands] arrived. */
        val baudAtCommand = mutableListOf<Int>()
        val flashPayload = ByteArrayOutputStream()
        var lastFlashBegin: Request? = null
        var lastFlashEnd: Request? = null
        var syncRequests = 0
            private set

        private val decoder = SlipDecoder()
        private val pending = ArrayDeque<Byte>()

        override fun write(data: ByteArray) {
            // A ROM that switched its UART but never answers there again.
            if (silentAtBaud != null && baudRate == silentAtBaud) return
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
            // A USB-UART bridge that accepts the ROM's switch but cannot follow it.
            if (baudRate == failSwitchToBaud) throw IOException("bridge cannot switch to $baudRate")
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
            baudAtCommand += baudRate
            if (alwaysSilent) return
            when (op) {
                EspCommands.SYNC -> {
                    syncRequests++
                    if (syncRequests > syncsToIgnore) {
                        // The real ROM answers a SYNC packet 8 times.
                        repeat(syncResponses) { respond(op) }
                    }
                }

                EspCommands.READ_REG -> {
                    noise(op)
                    respond(op, value = registerValue(request.u32(0)))
                }

                EspCommands.SPI_ATTACH, EspCommands.SPI_SET_PARAMS -> {
                    noise(op)
                    respond(op)
                }

                EspCommands.CHANGE_BAUDRATE -> {
                    when {
                        // The ROM ignores the request but keeps answering at the old rate.
                        ignoreBaudChange -> Unit
                        rejectBaudChange -> respond(op, status = 1, errorCode = 8)
                        else -> respond(op)
                    }
                }

                EspCommands.FLASH_BEGIN -> {
                    lastFlashBegin = request
                    respond(op)
                }

                EspCommands.FLASH_DEFL_BEGIN -> {
                    if (failDeflatedBegin) {
                        respond(op, status = 1, errorCode = 1)
                    } else {
                        lastFlashBegin = request
                        respond(op)
                    }
                }

                EspCommands.FLASH_DATA, EspCommands.FLASH_DEFL_DATA -> {
                    val chunk = payload.copyOfRange(16, payload.size)
                    val expected = EspBootloaderProtocol.checksum(chunk).toLong()
                    if (rejectDataCommands || request.checksum != expected) {
                        // 0x07 = CHECKSUM_ERROR
                        respond(op, status = 1, errorCode = 7)
                    } else {
                        flashPayload.write(chunk)
                        respond(op)
                    }
                }

                EspCommands.FLASH_END, EspCommands.FLASH_DEFL_END -> {
                    lastFlashEnd = request
                    respond(op)
                }

                else -> respond(op)
            }
        }

        /** A frame the parser must discard, e.g. boot text leaked into the stream. */
        private fun noise(op: Int) {
            if (!emitNoiseBeforeReplies) return
            val packet = byteArrayOf(0x02, op.toByte(), 0, 0, 0, 0, 0, 0)
            SlipCodec.encode(packet).forEach { pending.addLast(it) }
        }

        private fun registerValue(address: Long): Long = when (address) {
            EspFlasherEngine.CHIP_MAGIC_REGISTER -> chipMagic
            EspFlasherEngine.MAC_LOW_REGISTER -> macLow
            EspFlasherEngine.MAC_HIGH_REGISTER -> macHigh
            else -> 0L
        }

        private fun respond(
            op: Int,
            status: Int = 0,
            errorCode: Int = 0,
            value: Long = 0,
            data: ByteArray = ByteArray(0),
        ) {
            // The ROM appends [status, error, reserved, reserved] to every reply payload.
            val payload = data + byteArrayOf(status.toByte(), errorCode.toByte(), 0, 0)
            val packet = ByteArray(8 + payload.size)
            packet[0] = 0x01
            packet[1] = op.toByte()
            packet[2] = (payload.size and 0xFF).toByte()
            packet[3] = ((payload.size shr 8) and 0xFF).toByte()
            for (index in 0 until 4) {
                packet[4 + index] = ((value shr (8 * index)) and 0xFF).toByte()
            }
            payload.copyInto(packet, 8)
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
    fun `the extra sync responses are drained before reading the chip id`() {
        // The ROM replies 8 times to one SYNC; leaving 7 queued would shift every later reply.
        val device = FakeEspDevice(syncResponses = 8)
        val chip = engine(device).connect()
        assertEquals(1, device.syncRequests)
        assertEquals("ESP32", chip.name)
        assertEquals("cd:ab:34:12:0d:f0", chip.macAddress)
    }

    @Test
    fun `unparseable frames are skipped instead of failing the sequence`() {
        val device = FakeEspDevice(emitNoiseBeforeReplies = true)
        val chip = engine(device).connect()
        assertEquals("ESP32", chip.name)
        val outcome = engine(device).flash(ByteArray(1024) { 3 }, 0x0)
        assertTrue("unexpected outcome: $outcome", outcome is FlashOutcome.Success)
    }

    @Test
    fun `a rom status error fails the flash`() {
        val device = FakeEspDevice(rejectDataCommands = true)
        val outcome = engine(device).flash(ByteArray(2048) { 1 }, 0x10000)
        assertTrue("unexpected outcome: $outcome", outcome is FlashOutcome.Failure)
        assertTrue((outcome as FlashOutcome.Failure).message.contains("status=1"))
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
            // Never the all-zero or sum checksum the ROM would reject.
            if (chunk.isNotEmpty()) assertTrue(command.checksum != 0L)
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

        // The retry re-syncs, so the link has to drop back to the ROM's sync rate first.
        val syncIndices = device.commands.withIndex()
            .filter { it.value.op == EspCommands.SYNC }
            .map { it.index }
        assertEquals(2, syncIndices.size)
        assertEquals(115_200, device.baudAtCommand[syncIndices[1]])
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
    fun `a port opened at the flash rate syncs at 115200 and then raises the baud rate`() {
        val device = FakeEspDevice(initialBaudRate = 460_800)
        val config = FlashConfig(
            autoReset = false,
            resetAfterFlash = false,
            targetBaudRate = 460_800,
        )
        val chip = engine(device, config).connect()
        assertEquals("ESP32", chip.name)

        val syncIndex = device.commands.indexOfFirst { it.op == EspCommands.SYNC }
        assertTrue("no SYNC was sent", syncIndex >= 0)
        assertEquals(115_200, device.baudAtCommand[syncIndex])

        // The chip reads happen at the safe rate too, so a slow bridge still reports the chip.
        val chipReadIndex = device.commands.indexOfFirst { it.op == EspCommands.READ_REG }
        assertEquals(115_200, device.baudAtCommand[chipReadIndex])

        val changeIndex = device.commands.indexOfFirst { it.op == EspCommands.CHANGE_BAUDRATE }
        assertTrue("CHANGE_BAUDRATE was not sent", changeIndex > chipReadIndex)
        assertEquals(460_800L, device.commands[changeIndex].u32(0))
        assertEquals(115_200L, device.commands[changeIndex].u32(4))
        assertEquals(460_800, device.baudRate)
    }

    @Test
    fun `flash from a port opened at 460800 writes at the raised rate`() {
        val device = FakeEspDevice(initialBaudRate = 460_800)
        val config = FlashConfig(
            autoReset = false,
            resetAfterFlash = false,
            targetBaudRate = 460_800,
        )
        val outcome = engine(device, config).flash(ByteArray(2_048) { 1 }, 0x10000)
        assertTrue("unexpected outcome: $outcome", outcome is FlashOutcome.Success)

        val syncIndex = device.commands.indexOfFirst { it.op == EspCommands.SYNC }
        assertEquals(115_200, device.baudAtCommand[syncIndex])
        val dataIndex = device.commands.indexOfFirst { it.op == EspCommands.FLASH_DEFL_DATA }
        assertTrue("no flash data was sent", dataIndex > syncIndex)
        assertEquals(460_800, device.baudAtCommand[dataIndex])
    }

    @Test
    fun `no baud change is sent when the configured rate is the sync rate`() {
        val device = FakeEspDevice(initialBaudRate = 460_800)
        val config = FlashConfig(
            autoReset = false,
            resetAfterFlash = false,
            targetBaudRate = 115_200,
        )
        engine(device, config).connect()
        assertTrue(device.commands.none { it.op == EspCommands.CHANGE_BAUDRATE })
        assertEquals(115_200, device.baudRate)
    }

    @Test
    fun `a rejected baud change keeps 115200 and the flash still succeeds`() {
        val device = FakeEspDevice(initialBaudRate = 460_800, rejectBaudChange = true)
        val config = FlashConfig(
            autoReset = false,
            resetAfterFlash = false,
            targetBaudRate = 460_800,
        )
        val outcome = engine(device, config).flash(ByteArray(1_024) { 2 }, 0x10000)
        assertTrue("unexpected outcome: $outcome", outcome is FlashOutcome.Success)
        assertTrue(device.commands.any { it.op == EspCommands.CHANGE_BAUDRATE })
        assertEquals(115_200, device.baudRate)
        val dataIndex = device.commands.indexOfFirst { it.op == EspCommands.FLASH_DEFL_DATA }
        assertEquals(115_200, device.baudAtCommand[dataIndex])
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
    fun `baud rate change keeps the old rate when the bootloader ignores it`() {
        val device = FakeEspDevice(ignoreBaudChange = true)
        val engine = engine(device, FlashConfig(autoReset = false, responseTimeoutMs = 10))
        assertFalse(engine.changeBaudRate(460_800, 115_200))
        assertEquals(115_200, device.baudRate)
    }

    @Test
    fun `a bridge that cannot switch after the rom accepted is recovered by a reset and resync`() {
        val device = FakeEspDevice(failSwitchToBaud = 460_800)
        val config = FlashConfig(
            autoReset = true,
            resetAfterFlash = false,
            targetBaudRate = 460_800,
        )
        val outcome = engine(device, config).flash(ByteArray(1_024) { 3 }, 0x10000)
        assertTrue("unexpected outcome: $outcome", outcome is FlashOutcome.Success)
        assertEquals(115_200, device.baudRate)

        // connect's reset, the recovery reset, then the write at the safe rate.
        assertEquals(2, device.dtrRtsLog.size / 3)
        assertEquals(2, device.commands.count { it.op == EspCommands.SYNC })
        val dataIndex = device.commands.indexOfFirst { it.op == EspCommands.FLASH_DEFL_DATA }
        assertEquals(115_200, device.baudAtCommand[dataIndex])
    }

    @Test
    fun `a rom that goes silent at the new rate is recovered by a reset`() {
        val device = FakeEspDevice(silentAtBaud = 921_600)
        val config = FlashConfig(
            autoReset = true,
            resetAfterFlash = false,
            targetBaudRate = 460_800,
            responseTimeoutMs = 50,
        )
        val engine = engine(device, config)
        engine.connect()
        assertEquals(460_800, device.baudRate)

        assertFalse(engine.changeBaudRate(921_600, 460_800))
        assertEquals(115_200, device.baudRate)
        assertTrue(engine.isSynced)
    }

    @Test
    fun `a baud rate change the bootloader refuses keeps 115200`() {
        val device = FakeEspDevice(rejectBaudChange = true)
        val engine = engine(device, FlashConfig(autoReset = false, responseTimeoutMs = 10))
        assertFalse(engine.changeBaudRate(460_800, 115_200))
        assertEquals(115_200, device.baudRate)
    }
}
