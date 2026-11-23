package com.yuushilabs.esp32flasher.core

import java.util.zip.Inflater
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EspBootloaderProtocolTest {
    private data class Request(val op: Int, val checksum: Long, val payload: ByteArray)

    private fun parseRequest(frame: ByteArray): Request {
        val packet = SlipCodec.decode(frame)
        val op = packet[1].toInt() and 0xFF
        val size = (packet[2].toInt() and 0xFF) or ((packet[3].toInt() and 0xFF) shl 8)
        var checksum = 0L
        for (index in 0 until 4) {
            checksum = checksum or ((packet[4 + index].toLong() and 0xFFL) shl (8 * index))
        }
        return Request(op, checksum, packet.copyOfRange(8, 8 + size))
    }

    private fun u32(vararg values: Long): ByteArray {
        val out = ByteArray(values.size * 4)
        values.forEachIndexed { index, value ->
            for (byte in 0 until 4) {
                out[index * 4 + byte] = ((value shr (8 * byte)) and 0xFF).toByte()
            }
        }
        return out
    }

    private fun readU32(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 4) {
            value = value or ((data[offset + index].toLong() and 0xFFL) shl (8 * index))
        }
        return value
    }

    private fun responsePacket(op: Int, value: Long, data: ByteArray = ByteArray(0)): ByteArray {
        val packet = ByteArray(8 + data.size)
        packet[0] = 0x01
        packet[1] = op.toByte()
        packet[2] = (data.size and 0xFF).toByte()
        packet[3] = ((data.size shr 8) and 0xFF).toByte()
        for (index in 0 until 4) {
            packet[4 + index] = ((value shr (8 * index)) and 0xFF).toByte()
        }
        data.copyInto(packet, 8)
        return packet
    }

    @Test
    fun `request packet uses esptool header layout`() {
        val frame = EspBootloaderProtocol.requestFrame(EspCommands.READ_REG, u32(0x40001000))
        val request = parseRequest(frame)
        assertEquals(EspCommands.READ_REG, request.op)
        assertEquals(4, request.payload.size)
        assertEquals(0x40001000L, readU32(request.payload, 0))
        assertEquals(0L, request.checksum)
    }

    @Test
    fun `data checksum is the ROM XOR seeded with 0xEF`() {
        assertEquals(0xEF, EspBootloaderProtocol.checksum(ByteArray(0)))
        assertEquals(0x90, EspBootloaderProtocol.checksum(byteArrayOf(0x01, 0x02, 0x03, 0x7F)))
        // esptool: checksum(b"\x00") == 0xEF, checksum(b"\xff") == 0x10
        assertEquals(0xEF, EspBootloaderProtocol.checksum(byteArrayOf(0x00)))
        assertEquals(0x10, EspBootloaderProtocol.checksum(byteArrayOf(0xFF.toByte())))
        // Only data commands carry it; every other command sends 0.
        val attach = parseRequest(EspBootloaderProtocol.requestFrame(EspCommands.SPI_ATTACH, byteArrayOf(1, 2)))
        assertEquals(0L, attach.checksum)
        val data = parseRequest(EspBootloaderProtocol.requestFrame(EspCommands.FLASH_DATA, byteArrayOf(1, 2)))
        assertEquals(0xECL, data.checksum)
    }

    @Test
    fun `sync payload matches the ROM magic`() {
        val payload = EspBootloaderProtocol.syncPayload()
        assertEquals(36, payload.size)
        assertArrayEquals(
            byteArrayOf(0x07, 0x07, 0x12, 0x20),
            payload.copyOfRange(0, 4),
        )
        assertTrue(payload.copyOfRange(4, 36).all { it == 0x55.toByte() })
        assertEquals(0L, parseRequest(EspBootloaderProtocol.syncFrame()).checksum)
    }

    @Test
    fun `spi attach and set params encode the expected fields`() {
        val attach = parseRequest(EspBootloaderProtocol.spiAttachFrame())
        assertEquals(EspCommands.SPI_ATTACH, attach.op)
        assertEquals(8, attach.payload.size)
        assertEquals(0L, readU32(attach.payload, 0))
        assertEquals(0L, readU32(attach.payload, 4))

        val params = parseRequest(EspBootloaderProtocol.spiSetParamsFrame(totalSize = 0x400000))
        assertEquals(EspCommands.SPI_SET_PARAMS, params.op)
        assertEquals(24, params.payload.size)
        assertEquals(0L, readU32(params.payload, 0))
        assertEquals(0x400000L, readU32(params.payload, 4))
        assertEquals(0x10000L, readU32(params.payload, 8))
        assertEquals(0x1000L, readU32(params.payload, 12))
        assertEquals(0x100L, readU32(params.payload, 16))
        assertEquals(0xFFFFL, readU32(params.payload, 20))
    }

    @Test
    fun `flash begin selects compressed or plain opcode`() {
        val plain = parseRequest(
            EspBootloaderProtocol.flashBeginFrame(8192, 8, 1024, 0x10000, compressed = false),
        )
        assertEquals(EspCommands.FLASH_BEGIN, plain.op)
        assertEquals(16, plain.payload.size)
        assertEquals(8192L, readU32(plain.payload, 0))
        assertEquals(8L, readU32(plain.payload, 4))
        assertEquals(1024L, readU32(plain.payload, 8))
        assertEquals(0x10000L, readU32(plain.payload, 12))

        val compressed = parseRequest(
            EspBootloaderProtocol.flashBeginFrame(8192, 3, 1024, 0x0, compressed = true),
        )
        assertEquals(EspCommands.FLASH_DEFL_BEGIN, compressed.op)
    }

    @Test
    fun `flash data frame carries sequence and checksum`() {
        val chunk = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        val request = parseRequest(EspBootloaderProtocol.flashDataFrame(chunk, 7, compressed = true))
        assertEquals(EspCommands.FLASH_DEFL_DATA, request.op)
        assertEquals(16 + chunk.size, request.payload.size)
        assertEquals(chunk.size.toLong(), readU32(request.payload, 0))
        assertEquals(7L, readU32(request.payload, 4))
        assertEquals(0L, readU32(request.payload, 8))
        assertEquals(0L, readU32(request.payload, 12))
        assertArrayEquals(chunk, request.payload.copyOfRange(16, request.payload.size))
        assertEquals(0xAFL, request.checksum)
    }

    @Test
    fun `flash end frame selects opcode and reboot flag`() {
        val plain = parseRequest(EspBootloaderProtocol.flashEndFrame(stayInLoader = false, compressed = false))
        assertEquals(EspCommands.FLASH_END, plain.op)
        assertEquals(0L, readU32(plain.payload, 0))

        val compressed = parseRequest(EspBootloaderProtocol.flashEndFrame(stayInLoader = true, compressed = true))
        assertEquals(EspCommands.FLASH_DEFL_END, compressed.op)
        assertEquals(1L, readU32(compressed.payload, 0))
    }

    @Test
    fun `change baud rate and erase commands encode arguments`() {
        val change = parseRequest(EspBootloaderProtocol.changeBaudFrame(460800, 115200))
        assertEquals(EspCommands.CHANGE_BAUDRATE, change.op)
        assertEquals(460800L, readU32(change.payload, 0))
        assertEquals(115200L, readU32(change.payload, 4))

        val erase = parseRequest(EspBootloaderProtocol.eraseRegionFrame(0x10000, 0x1000))
        assertEquals(EspCommands.ERASE_REGION, erase.op)
        assertEquals(0x10000L, readU32(erase.payload, 0))
        assertEquals(0x1000L, readU32(erase.payload, 4))
    }

    @Test
    fun `parse response reads value and data`() {
        val parsed = EspBootloaderProtocol.parseResponse(
            responsePacket(EspCommands.READ_REG, 0x00F01D83L, byteArrayOf(0x01, 0x02)),
        )
        assertEquals(EspCommands.READ_REG, parsed.command)
        assertEquals(0x00F01D83L, parsed.value)
        assertArrayEquals(byteArrayOf(0x01, 0x02), parsed.data)
        assertFalse(parsed.isSuccess)
    }

    @Test
    fun `response status comes from the payload, not from the value field`() {
        // READ_REG: value is the register magic, the payload is [status, error, 0, 0].
        val register = EspBootloaderProtocol.parseResponse(
            responsePacket(EspCommands.READ_REG, 0x00F01D83L, byteArrayOf(0x00, 0x00, 0x00, 0x00)),
        )
        assertEquals(0x00F01D83L, register.value)
        assertTrue(register.isSuccess)
        assertEquals(0, register.errorCode)

        // FLASH_DATA: value echoes the checksum (non-zero), status 1 / error 7 must fail.
        val failed = EspBootloaderProtocol.parseResponse(
            responsePacket(EspCommands.FLASH_DATA, 0xAF, byteArrayOf(0x01, 0x07, 0x00, 0x00)),
        )
        assertEquals(1, failed.status)
        assertEquals(7, failed.errorCode)
        assertFalse(failed.isSuccess)
    }

    @Test
    fun `parse response rejects wrong direction and truncated frames`() {
        val wrongDirection = responsePacket(EspCommands.SYNC, 0).also { it[0] = 0x00 }
        assertThrows(EspProtocolException::class.java) {
            EspBootloaderProtocol.parseResponse(wrongDirection)
        }
        assertThrows(EspProtocolException::class.java) {
            EspBootloaderProtocol.parseResponse(byteArrayOf(0x01, 0x08, 0x00))
        }
        val truncated = responsePacket(EspCommands.SYNC, 0, ByteArray(4)).copyOf(10)
        assertThrows(EspProtocolException::class.java) {
            EspBootloaderProtocol.parseResponse(truncated)
        }
    }

    @Test
    fun `response equality compares payload contents`() {
        val first = EspResponse(EspCommands.SYNC, 0, byteArrayOf(1, 2))
        val second = EspResponse(EspCommands.SYNC, 0, byteArrayOf(1, 2))
        val third = EspResponse(EspCommands.SYNC, 0, byteArrayOf(1, 3))
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(first, third)
    }

    @Test
    fun `erase size aligns to whole sectors including an unaligned head`() {
        assertEquals(4096, EspBootloaderProtocol.eraseSize(0x0, 1))
        assertEquals(4096, EspBootloaderProtocol.eraseSize(0x0, 4096))
        assertEquals(8192, EspBootloaderProtocol.eraseSize(0x0, 4097))
        assertEquals(4096, EspBootloaderProtocol.eraseSize(0x2, 10))
        assertEquals(8192, EspBootloaderProtocol.eraseSize(0x10000, 5000))
        assertEquals(4096, EspBootloaderProtocol.eraseSize(0xFFF, 1))
    }

    @Test
    fun `erase size rejects empty images`() {
        assertThrows(IllegalArgumentException::class.java) {
            EspBootloaderProtocol.eraseSize(0, 0)
        }
    }

    @Test
    fun `num blocks rounds up`() {
        assertEquals(0, EspBootloaderProtocol.numBlocks(0))
        assertEquals(1, EspBootloaderProtocol.numBlocks(1))
        assertEquals(1, EspBootloaderProtocol.numBlocks(1024))
        assertEquals(2, EspBootloaderProtocol.numBlocks(1025))
        assertEquals(8, EspBootloaderProtocol.numBlocks(8192))
    }

    @Test
    fun `pad to erase size fills with 0xFF`() {
        val padded = EspBootloaderProtocol.padToEraseSize(byteArrayOf(0x01, 0x02, 0x03), 0)
        assertEquals(4096, padded.size)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), padded.copyOfRange(0, 3))
        assertTrue(padded.copyOfRange(3, padded.size).all { it == 0xFF.toByte() })
    }

    @Test
    fun `compress produces a zlib stream`() {
        val image = ByteArray(3000) { (it % 7).toByte() }
        val compressed = EspBootloaderProtocol.compress(image)
        assertTrue(compressed.size > 2)
        assertEquals(0x78, compressed[0].toInt() and 0xFF)

        val inflater = Inflater()
        inflater.setInput(compressed)
        val restored = ByteArray(image.size)
        val read = inflater.inflate(restored)
        inflater.end()
        assertEquals(image.size, read)
        assertArrayEquals(image, restored)
    }

    @Test
    fun `split produces block sized chunks`() {
        val data = ByteArray(2500) { it.toByte() }
        val chunks = EspBootloaderProtocol.split(data, 1024)
        assertEquals(3, chunks.size)
        assertEquals(1024, chunks[0].size)
        assertEquals(1024, chunks[1].size)
        assertEquals(452, chunks[2].size)
        assertArrayEquals(data, chunks.reduce { acc, chunk -> acc + chunk })
        assertTrue(EspBootloaderProtocol.split(ByteArray(0)).isEmpty())
    }

    @Test
    fun `flash plan for uncompressed images pads and counts blocks`() {
        val image = ByteArray(5000) { 0x5A }
        val plan = EspBootloaderProtocol.flashPlan(image, 0x10000, compression = false)
        assertEquals(8192, plan.eraseSize)
        assertEquals(8192, plan.payload.size)
        assertEquals(8, plan.numBlocks)
        assertFalse(plan.compressed)
        assertEquals(8192, plan.uncompressedSize)
        assertEquals(8, plan.chunkCount)
        assertArrayEquals(image, plan.payload.copyOfRange(0, image.size))
    }

    @Test
    fun `flash plan for compressed images reports the uncompressed size`() {
        val image = ByteArray(5000) { 0x00 }
        val plan = EspBootloaderProtocol.flashPlan(image, 0x0, compression = true)
        assertTrue(plan.compressed)
        assertEquals(8192, plan.uncompressedSize)
        assertEquals(8192, plan.eraseSize)
        assertEquals(EspBootloaderProtocol.numBlocks(plan.payload.size), plan.numBlocks)
        assertEquals(plan.payload.size, plan.chunks().sumOf { it.size })
    }

    @Test
    fun `flash plan rejects empty images`() {
        assertThrows(IllegalArgumentException::class.java) {
            EspBootloaderProtocol.flashPlan(ByteArray(0), 0)
        }
    }

    @Test
    fun `chip magic detection and mac formatting`() {
        assertEquals("ESP32", EspBootloaderProtocol.detectChip(0x00F01D83))
        assertEquals("ESP32-S3", EspBootloaderProtocol.detectChip(0x00000909))
        assertEquals("ESP32-C3", EspBootloaderProtocol.detectChip(0x1B31506F))
        assertNull(EspBootloaderProtocol.detectChip(0x12345678))
        assertEquals("cd:ab:34:12:0d:f0", EspBootloaderProtocol.formatMac(0x0000F00D, 0x1234ABCD))
    }
}
