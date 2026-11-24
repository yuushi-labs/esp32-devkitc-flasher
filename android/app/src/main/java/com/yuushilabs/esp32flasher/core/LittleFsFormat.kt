package com.yuushilabs.esp32flasher.core

/**
 * On-disk constants and helpers of littlefs v2.1, shared by [LittleFsImageBuilder] and
 * [LittleFsReader].
 *
 * The values follow the littlefs specification (`littlefs/SPEC.md`, on-disk version 2.1) and
 * `lfs.c` (revision used by esp_littlefs and Arduino-ESP32 3.x):
 *
 * - little-endian for everything except the 32-bit metadata tag words, which are big-endian
 * - CRC-32 with polynomial `0x04c11db7`, initial value `0xffffffff`, reflecting nibble table
 * - directory metadata blocks are append-only logs of commits; a commit ends with a CRC tag
 */
internal object LittleFsFormat {
    /** On-disk version 2.1: superblock carries a CRC in 2.1? No - it carries the same fields, the
     * difference is the optional forward CRC tag (FCRC) in metadata commits. */
    const val DISK_VERSION = 0x00020001

    const val MAGIC = "littlefs"
    const val SUPERBLOCK_SIZE = 24

    /** `LFS_BLOCK_NULL`. */
    const val BLOCK_NULL = 0xFFFFFFFFL

    /** Tag type of a file name; the low 8 bits of the type carry the file kind. */
    const val TYPE_NAME = 0x000
    const val TYPE_REG = 0x001
    const val TYPE_DIR = 0x002
    const val TYPE_SUPERBLOCK = 0x0FF

    const val TYPE_STRUCT = 0x200
    const val TYPE_DIRSTRUCT = 0x200
    const val TYPE_INLINESTRUCT = 0x201
    const val TYPE_CTZSTRUCT = 0x202

    const val TYPE_USERATTR = 0x300
    const val TYPE_SPLICE = 0x400
    const val TYPE_CREATE = 0x401
    const val TYPE_DELETE = 0x4FF
    const val TYPE_CRC = 0x500
    const val TYPE_FCRC = 0x5FF
    const val TYPE_TAIL = 0x600
    const val TYPE_SOFTTAIL = 0x600
    const val TYPE_HARDTAIL = 0x601
    const val TYPE_GSTATE = 0x700
    const val TYPE_MOVESTATE = 0x7FF

    /** Id used by tags that are not tied to a file (directory / global metadata). */
    const val ID_GLOBAL = 0x3FF

    /** Size value marking a deleted tag. */
    const val SIZE_DELETE = 0x3FF

    /** Largest data area a single CRC tag can describe (the size field is 10 bits). */
    const val CRC_MAX_DATA = 0x3FE

    fun makeTag(type: Int, id: Int, size: Int): Long =
        ((type.toLong() and 0x7FFL) shl 20) or
            ((id.toLong() and 0x3FFL) shl 10) or
            (size.toLong() and 0x3FFL)

    /** Abstract type (bits 30..28): 0 name, 1 splice, 2 struct, 3 userattr, 4 ?, 5 crc, 6 tail, 7 gstate. */
    fun type1(tag: Long): Int = ((tag shr 28) and 0x7).toInt()

    fun type3(tag: Long): Int = ((tag shr 20) and 0x7FF).toInt()

    fun chunk(tag: Long): Int = ((tag shr 20) and 0xFF).toInt()

    fun id(tag: Long): Int = ((tag shr 10) and 0x3FF).toInt()

    fun size(tag: Long): Int = (tag and 0x3FF).toInt()

    fun isValid(tag: Long): Boolean = (tag and 0x80000000L) == 0L

    fun isDelete(tag: Long): Boolean = (tag and 0x3FF) == 0x3FFL

    /** Total on-disk size of an entry: its tag word plus its data. */
    fun diskSize(tag: Long): Int = 4 + (if (isDelete(tag)) 0 else size(tag))

    // ---- CRC-32 (polynomial 0x04c11db7, as in lfs_util.c) ----

    private val CRC_TABLE = longArrayOf(
        0x00000000L, 0x1db71064L, 0x3b6e20c8L, 0x26d930acL,
        0x76dc4190L, 0x6b6b51f4L, 0x4db26158L, 0x5005713cL,
        0xedb88320L, 0xf00f9344L, 0xd6d6a3e8L, 0xcb61b38cL,
        0x9b64c2b0L, 0x86d3d2d4L, 0xa00ae278L, 0xbdbdf21cL,
    )

    fun crc(seed: Int, data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var value = seed
        for (index in from until to) {
            val byte = data[index].toInt() and 0xFF
            value = (value ushr 4) xor CRC_TABLE[(value xor byte) and 0xF].toInt()
            value = (value ushr 4) xor CRC_TABLE[(value xor (byte ushr 4)) and 0xF].toInt()
        }
        return value
    }

    // ---- littlefs CTZ skip-list geometry ----

    /** `block_size - 2 * sizeof(uint32)`; the data stride of one CTZ block. */
    fun ctzStride(blockSize: Int): Int = blockSize - 8

    /** `lfs_ctz_index`: block index that holds file offset [position]. */
    fun ctzIndex(position: Long, blockSize: Int): Int {
        val stride = ctzStride(blockSize)
        var index = position / stride
        if (index == 0L) return 0
        index = (position - 4L * (java.lang.Long.bitCount(index - 1) + 2)) / stride
        return index.toInt()
    }

    /** Number of skip pointers in front of the data of block [index]. */
    fun ctzSkips(index: Int): Int =
        if (index == 0) 0 else Integer.numberOfTrailingZeros(index) + 1

    /** Offset of the first data byte of [index] inside its own block (after the skip pointers). */
    fun ctzDataOffset(index: Int, blockSize: Int): Int {
        require(blockSize > 0)
        return 4 * ctzSkips(index)
    }

    /** File offset of the first data byte stored in block [index]. */
    fun ctzFileOffset(index: Int, blockSize: Int): Long =
        if (index == 0) {
            0L
        } else {
            ctzStride(blockSize).toLong() * index +
                4L * (Integer.bitCount(index) + Integer.numberOfTrailingZeros(index) + 1)
        }

    /** Number of file bytes a CTZ block with [index] can hold. */
    fun ctzDataCapacity(index: Int, blockSize: Int): Int =
        blockSize - ctzDataOffset(index, blockSize)

    /** Number of CTZ blocks needed for [size] bytes. */
    fun ctzBlockCount(size: Long, blockSize: Int): Int =
        if (size <= 0L) 0 else ctzIndex(size - 1, blockSize) + 1

    /** `lfs_npw2`: the smallest power of two greater than or equal to [value] (0 for 0 and 1). */
    fun npw2(value: Int): Int {
        if (value <= 1) return 0
        return 32 - Integer.numberOfLeadingZeros(value - 1)
    }

    // ---- little-endian helpers ----

    fun readUInt32(image: ByteArray, offset: Int): Long {
        if (offset < 0 || offset > image.size - 4) {
            throw LittleFsFormatException("littlefs イメージの範囲外を読み取ろうとしました (offset=$offset)")
        }
        var value = 0L
        for (index in 0 until 4) {
            value = value or ((image[offset + index].toLong() and 0xFFL) shl (8 * index))
        }
        return value
    }

    fun writeUInt32(image: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) {
            image[offset + index] = ((value ushr (8 * index)) and 0xFFL).toByte()
        }
    }

    fun readUInt32BigEndian(image: ByteArray, offset: Int): Long {
        if (offset < 0 || offset > image.size - 4) {
            throw LittleFsFormatException("littlefs イメージの範囲外を読み取ろうとしました (offset=$offset)")
        }
        var value = 0L
        for (index in 0 until 4) {
            value = (value shl 8) or (image[offset + index].toLong() and 0xFFL)
        }
        return value
    }

    fun writeUInt32BigEndian(image: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) {
            image[offset + index] = ((value ushr (8 * (3 - index))) and 0xFFL).toByte()
        }
    }

    /** Unsigned byte-wise comparison, the order littlefs keeps names in. */
    fun compareNames(left: String, right: String): Int {
        val a = left.toByteArray(Charsets.UTF_8)
        val b = right.toByteArray(Charsets.UTF_8)
        val limit = minOf(a.size, b.size)
        for (index in 0 until limit) {
            val diff = (a[index].toInt() and 0xFF) - (b[index].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }
}

class LittleFsFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
