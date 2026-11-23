package com.yuushilabs.esp32flasher.core

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/** ROM bootloader opcodes shared by the ESP32 family. */
object EspCommands {
    const val FLASH_BEGIN = 0x02
    const val FLASH_DATA = 0x03
    const val FLASH_END = 0x04
    const val MEM_BEGIN = 0x05
    const val MEM_END = 0x06
    const val MEM_DATA = 0x07
    const val SYNC = 0x08
    const val WRITE_REG = 0x09
    const val READ_REG = 0x0A
    const val SPI_SET_PARAMS = 0x0B
    const val SPI_ATTACH = 0x0D
    const val CHANGE_BAUDRATE = 0x0F
    const val FLASH_DEFL_BEGIN = 0x10
    const val FLASH_DEFL_DATA = 0x11
    const val FLASH_DEFL_END = 0x12
    const val GET_SECURITY_INFO = 0x14
    const val ERASE_FLASH = 0xD0
    const val ERASE_REGION = 0xD1
    const val READ_FLASH = 0xD2

    /** Commands whose request carriage carries a data checksum in the header. */
    fun isFlashData(op: Int): Boolean = op == FLASH_DATA || op == FLASH_DEFL_DATA || op == MEM_DATA
}

/**
 * A parsed ROM bootloader response: `direction | op | size | value | data`.
 *
 * The header `value` field is *not* a status: the ROM puts the checksum of the received block
 * there for data commands and the register value for `READ_REG`. The status lives in the first
 * two payload bytes (status, error), followed by two reserved bytes.
 */
data class EspResponse(
    val command: Int,
    val value: Long,
    val data: ByteArray,
) {
    /** Whether the payload carried the status bytes the ROM appends to every reply. */
    val hasStatus: Boolean get() = data.size >= 2

    val status: Int get() = if (data.isNotEmpty()) data[0].toInt() and 0xFF else 0

    val errorCode: Int get() = if (data.size >= 2) data[1].toInt() and 0xFF else 0

    val isSuccess: Boolean get() = hasStatus && status == 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EspResponse) return false
        return command == other.command && value == other.value && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = command
        result = 31 * result + value.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }

    override fun toString(): String =
        "EspResponse(command=0x%02X, value=%d, data=%d bytes)".format(command, value, data.size)
}

/** Everything needed to stream one image to the flash. */
data class FlashPlan(
    val offset: Long,
    val eraseSize: Int,
    val numBlocks: Int,
    val blockSize: Int,
    /** Bytes that are actually transferred (compressed or padded). */
    val payload: ByteArray,
    val compressed: Boolean,
    val uncompressedSize: Int,
) {
    val chunkCount: Int get() = if (payload.isEmpty()) 0 else (payload.size + blockSize - 1) / blockSize

    fun chunks(): List<ByteArray> = EspBootloaderProtocol.split(payload, blockSize)
}

/**
 * Builds ESP32 ROM bootloader commands and parses its responses. Pure Kotlin so the whole
 * protocol layer is exercised by JVM unit tests.
 */
object EspBootloaderProtocol {
    const val BLOCK_SIZE = 0x400
    const val SECTOR_SIZE = 0x1000
    const val FLASH_BLOCK_SIZE = 0x10000
    const val PAGE_SIZE = 0x100
    const val DEFAULT_STATUS_MASK = 0xFFFF
    const val COMPRESSION_LEVEL = 9

    /** Seed of the ROM data checksum (`ESP_CHECKSUM_MAGIC` in esptool). */
    const val CHECKSUM_SEED = 0xEF

    private const val SYNC_DATA = 0x07
    private const val SYNC_DATA_2 = 0x12
    private const val SYNC_DATA_3 = 0x20
    private const val SYNC_FILLER = 0x55
    private const val SYNC_FILLER_COUNT = 32

    private const val DIRECTION_REQUEST = 0x00
    private const val DIRECTION_RESPONSE = 0x01

    /** Chip magic values read from `0x40001000`, as used by esptool for chip detection. */
    private val CHIP_MAGIC: Map<Long, String> = mapOf(
        0x00f01d83L to "ESP32",
        0x000007c6L to "ESP32-S2",
        0x00000909L to "ESP32-S3",
        0x1b31506fL to "ESP32-C3",
        0x6f51306fL to "ESP32-C2",
        0x2ce0806fL to "ESP32-C6",
        0x0332726fL to "ESP32-H2",
        0x0addbad0L to "ESP32-P4",
    )

    fun requestPacket(op: Int, data: ByteArray, checksum: Int): ByteArray {
        val out = ByteArrayOutputStream(data.size + 8)
        out.write(DIRECTION_REQUEST)
        out.write(op)
        writeUInt16(out, data.size)
        writeUInt32(out, checksum.toLong() and 0xFFFFFFFFL)
        out.write(data)
        return out.toByteArray()
    }

    /**
     * SLIP frame for [op]. Only data commands carry a checksum (XOR of the block starting from
     * [CHECKSUM_SEED]); every other command sends 0, exactly like `esptool`.
     */
    fun requestFrame(op: Int, data: ByteArray = ByteArray(0), checksum: Int? = null): ByteArray =
        SlipCodec.encode(
            requestPacket(
                op,
                data,
                checksum ?: if (EspCommands.isFlashData(op)) EspBootloaderProtocol.checksum(data) else 0,
            ),
        )

    /**
     * ROM checksum: XOR of every data byte starting from [CHECKSUM_SEED] (0xEF), which is what the
     * bootloader verifies for `MEM_DATA`, `FLASH_DATA` and `FLASH_DEFL_DATA`.
     */
    fun checksum(data: ByteArray, seed: Int = CHECKSUM_SEED): Int {
        var value = seed and 0xFF
        for (byte in data) {
            value = value xor (byte.toInt() and 0xFF)
        }
        return value and 0xFF
    }

    fun parseResponse(frame: ByteArray): EspResponse {
        if (frame.size < 8) {
            throw EspProtocolException("応答フレームが短すぎます (${frame.size} バイト)")
        }
        val direction = frame[0].toInt() and 0xFF
        if (direction != DIRECTION_RESPONSE) {
            throw EspProtocolException("応答の方向バイトが不正です: 0x%02X".format(direction))
        }
        val op = frame[1].toInt() and 0xFF
        val size = readUInt16(frame, 2)
        val value = readUInt32(frame, 4)
        if (frame.size < 8 + size) {
            throw EspProtocolException("応答データが不足しています (必要 $size バイト)")
        }
        return EspResponse(op, value, frame.copyOfRange(8, 8 + size))
    }

    fun syncPayload(): ByteArray {
        val out = ByteArrayOutputStream(36)
        out.write(SYNC_DATA)
        out.write(SYNC_DATA)
        out.write(SYNC_DATA_2)
        out.write(SYNC_DATA_3)
        repeat(SYNC_FILLER_COUNT) { out.write(SYNC_FILLER) }
        return out.toByteArray()
    }

    fun syncFrame(): ByteArray = requestFrame(EspCommands.SYNC, syncPayload())

    fun spiAttachFrame(): ByteArray = requestFrame(EspCommands.SPI_ATTACH, uint32(0) + uint32(0))

    fun spiSetParamsFrame(
        totalSize: Int = 0x400000,
        blockSize: Int = FLASH_BLOCK_SIZE,
        sectorSize: Int = SECTOR_SIZE,
        pageSize: Int = PAGE_SIZE,
        statusMask: Int = DEFAULT_STATUS_MASK,
    ): ByteArray = requestFrame(
        EspCommands.SPI_SET_PARAMS,
        uint32(0) + uint32(totalSize) + uint32(blockSize) + uint32(sectorSize) + uint32(pageSize) +
            uint32(statusMask),
    )

    fun flashBeginFrame(
        size: Int,
        numBlocks: Int,
        blockSize: Int = BLOCK_SIZE,
        offset: Long,
        compressed: Boolean = false,
    ): ByteArray = requestFrame(
        if (compressed) EspCommands.FLASH_DEFL_BEGIN else EspCommands.FLASH_BEGIN,
        uint32(size) + uint32(numBlocks) + uint32(blockSize) + uint32(offset),
    )

    fun flashDataFrame(chunk: ByteArray, seq: Int, compressed: Boolean = false): ByteArray = requestFrame(
        if (compressed) EspCommands.FLASH_DEFL_DATA else EspCommands.FLASH_DATA,
        uint32(chunk.size) + uint32(seq) + uint32(0) + uint32(0) + chunk,
        EspBootloaderProtocol.checksum(chunk),
    )

    fun flashEndFrame(stayInLoader: Boolean = false, compressed: Boolean = false): ByteArray = requestFrame(
        if (compressed) EspCommands.FLASH_DEFL_END else EspCommands.FLASH_END,
        uint32(if (stayInLoader) 1 else 0),
    )

    fun readRegFrame(address: Long): ByteArray = requestFrame(EspCommands.READ_REG, uint32(address))

    /**
     * CHANGE_BAUDRATE for the ROM bootloader. The ROM ignores the second word; esptool only fills
     * it with the outgoing rate when it talks to the stub loader instead.
     */
    fun changeBaudFrame(newBaud: Int, oldBaudForStub: Int = 0): ByteArray =
        requestFrame(EspCommands.CHANGE_BAUDRATE, uint32(newBaud) + uint32(oldBaudForStub))

    fun eraseFlashFrame(): ByteArray = requestFrame(EspCommands.ERASE_FLASH, uint32(0))

    fun eraseRegionFrame(offset: Long, size: Int): ByteArray =
        requestFrame(EspCommands.ERASE_REGION, uint32(offset) + uint32(size))

    /**
     * Number of bytes the ROM erases for an image of [size] at [offset]: the whole touched
     * 4 KB sectors, including the unaligned head.
     */
    fun eraseSize(offset: Long, size: Int, sectorSize: Int = SECTOR_SIZE): Int {
        require(size > 0) { "書き込みサイズは 1 バイト以上である必要があります" }
        val head = (offset % sectorSize).toInt()
        val total = head + size
        val remainder = total % sectorSize
        return if (remainder == 0) total else total + (sectorSize - remainder)
    }

    fun numBlocks(size: Int, blockSize: Int = BLOCK_SIZE): Int =
        if (size <= 0) 0 else (size + blockSize - 1) / blockSize

    fun padToEraseSize(image: ByteArray, offset: Long, fill: Byte = 0xFF.toByte()): ByteArray {
        val target = eraseSize(offset, image.size)
        if (image.size >= target) return image
        val padded = ByteArray(target)
        image.copyInto(padded)
        for (index in image.size until target) {
            padded[index] = fill
        }
        return padded
    }

    /** zlib-compresses the image the same way `esptool` does (level 9, zlib wrapper). */
    fun compress(image: ByteArray, level: Int = COMPRESSION_LEVEL): ByteArray {
        if (image.isEmpty()) return ByteArray(0)
        val deflater = Deflater(level)
        try {
            deflater.setInput(image)
            deflater.finish()
            val out = ByteArrayOutputStream(image.size / 2 + 64)
            val buffer = ByteArray(8 * 1024)
            while (!deflater.finished()) {
                val written = deflater.deflate(buffer)
                if (written <= 0) break
                out.write(buffer, 0, written)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    fun split(data: ByteArray, chunkSize: Int = BLOCK_SIZE): List<ByteArray> {
        require(chunkSize > 0) { "チャンクサイズは 1 バイト以上である必要があります" }
        if (data.isEmpty()) return emptyList()
        val chunks = ArrayList<ByteArray>((data.size + chunkSize - 1) / chunkSize)
        var index = 0
        while (index < data.size) {
            val end = minOf(index + chunkSize, data.size)
            chunks += data.copyOfRange(index, end)
            index = end
        }
        return chunks
    }

    /**
     * Builds the transfer plan for [image]: sector aligned padding, optional zlib compression and
     * the resulting block layout expected by FLASH_BEGIN / FLASH_DEFL_BEGIN.
     */
    fun flashPlan(image: ByteArray, offset: Long, compression: Boolean = true): FlashPlan {
        require(image.isNotEmpty()) { "書き込むイメージが空です" }
        val padded = padToEraseSize(image, offset)
        if (!compression) {
            return FlashPlan(
                offset = offset,
                eraseSize = padded.size,
                numBlocks = numBlocks(padded.size),
                blockSize = BLOCK_SIZE,
                payload = padded,
                compressed = false,
                uncompressedSize = padded.size,
            )
        }
        val compressed = compress(padded)
        return FlashPlan(
            offset = offset,
            eraseSize = padded.size,
            numBlocks = numBlocks(compressed.size),
            blockSize = BLOCK_SIZE,
            payload = compressed,
            compressed = true,
            uncompressedSize = padded.size,
        )
    }

    fun detectChip(magic: Long): String? = CHIP_MAGIC[magic and 0xFFFFFFFFL]

    fun formatMac(high: Long, low: Long): String {
        val value = ((high and 0xFFFFL) shl 32) or (low and 0xFFFFFFFFL)
        val bytes = ByteArray(6)
        for (index in 0 until 6) {
            bytes[index] = ((value shr (8 * index)) and 0xFF).toByte()
        }
        return bytes.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun uint32(value: Int): ByteArray = uint32(value.toLong() and 0xFFFFFFFFL)

    private fun uint32(value: Long): ByteArray {
        val normalized = value and 0xFFFFFFFFL
        return byteArrayOf(
            (normalized and 0xFF).toByte(),
            ((normalized shr 8) and 0xFF).toByte(),
            ((normalized shr 16) and 0xFF).toByte(),
            ((normalized shr 24) and 0xFF).toByte(),
        )
    }

    private fun writeUInt16(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }

    private fun writeUInt32(out: ByteArrayOutputStream, value: Long) {
        out.write((value and 0xFF).toInt())
        out.write(((value shr 8) and 0xFF).toInt())
        out.write(((value shr 16) and 0xFF).toInt())
        out.write(((value shr 24) and 0xFF).toInt())
    }

    private fun readUInt16(buffer: ByteArray, offset: Int): Int =
        (buffer[offset].toInt() and 0xFF) or ((buffer[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUInt32(buffer: ByteArray, offset: Int): Long {
        var result = 0L
        for (index in 0 until 4) {
            result = result or ((buffer[offset + index].toLong() and 0xFFL) shl (8 * index))
        }
        return result
    }
}

class EspProtocolException(message: String) : Exception(message)
