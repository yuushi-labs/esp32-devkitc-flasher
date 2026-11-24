package com.yuushilabs.esp32flasher.core

import java.security.MessageDigest

/** One entry of the ESP32 partition table as flashed at [PartitionTableParser.TABLE_OFFSET]. */
data class PartitionEntry(
    val label: String,
    val type: Int,
    val subtype: Int,
    val offset: Long,
    val size: Long,
    val flags: Long = 0L,
) {
    val typeLabel: String
        get() = when (type) {
            TYPE_APP -> "app"
            TYPE_DATA -> "data"
            else -> "0x%02x".format(type)
        }

    val subtypeLabel: String
        get() = when {
            type == TYPE_APP && subtype == SUBTYPE_FACTORY -> "factory"
            type == TYPE_APP && subtype in 0x10..0x1F -> "ota_${subtype - 0x10}"
            type == TYPE_DATA && subtype == SUBTYPE_OTA -> "ota"
            type == TYPE_DATA && subtype == SUBTYPE_PHY -> "phy"
            type == TYPE_DATA && subtype == SUBTYPE_NVS -> "nvs"
            type == TYPE_DATA && subtype == SUBTYPE_COREDUMP -> "coredump"
            type == TYPE_DATA && subtype == SUBTYPE_NVSKEYS -> "nvs_keys"
            type == TYPE_DATA && subtype == SUBTYPE_ESPHTTPD -> "esphttpd"
            type == TYPE_DATA && subtype == SUBTYPE_FAT -> "fat"
            type == TYPE_DATA && subtype == SUBTYPE_SPIFFS -> "spiffs"
            type == TYPE_DATA && subtype == SUBTYPE_LITTLEFS -> "littlefs"
            else -> "0x%02x".format(subtype)
        }

    /** True for the partition types that hold a filesystem image this app can read or write. */
    val isFilesystem: Boolean
        get() = type == TYPE_DATA && subtype in
            setOf(SUBTYPE_SPIFFS, SUBTYPE_LITTLEFS, SUBTYPE_FAT)

    val isLittleFs: Boolean
        get() = type == TYPE_DATA && subtype == SUBTYPE_LITTLEFS

    val isSpiffs: Boolean
        get() = type == TYPE_DATA && subtype == SUBTYPE_SPIFFS

    val endOffset: Long get() = offset + size

    companion object {
        const val TYPE_APP = 0x00
        const val TYPE_DATA = 0x01
        const val SUBTYPE_FACTORY = 0x00
        const val SUBTYPE_OTA = 0x00
        const val SUBTYPE_PHY = 0x01
        const val SUBTYPE_NVS = 0x02
        const val SUBTYPE_COREDUMP = 0x03
        const val SUBTYPE_NVSKEYS = 0x04
        const val SUBTYPE_ESPHTTPD = 0x80
        const val SUBTYPE_FAT = 0x81
        const val SUBTYPE_SPIFFS = 0x82
        const val SUBTYPE_LITTLEFS = 0x83
    }
}

/** A parsed partition table plus what could be verified about it. */
data class PartitionTable(
    val entries: List<PartitionEntry>,
    /** `null` when the table carries no MD5 entry, else whether the checksum matched. */
    val md5Valid: Boolean?,
    val tableBytes: Int,
) {
    val filesystems: List<PartitionEntry> get() = entries.filter { it.isFilesystem }

    val littleFs: PartitionEntry? get() = entries.firstOrNull { it.isLittleFs }

    val spiffs: PartitionEntry? get() = entries.firstOrNull { it.isSpiffs }

    /** The filesystem partition this app should default to: littlefs first, then spiffs. */
    val preferredFilesystem: PartitionEntry? get() = littleFs ?: spiffs

    fun find(label: String): PartitionEntry? = entries.firstOrNull { it.label == label }
}

class PartitionTableException(message: String) : Exception(message)

/**
 * Parses the ESP32 binary partition table.
 *
 * The table lives at `0x8000` (4 KB at most) and consists of 32-byte entries:
 * `magic(0xAA50, LE) | type | subtype | offset(LE) | size(LE) | label(16) | flags(LE)`.
 * An optional final entry with magic `0xEBEB` stores the MD5 of every preceding byte.
 */
object PartitionTableParser {
    const val TABLE_OFFSET = 0x8000
    const val TABLE_MAX_SIZE = 0x1000
    const val ENTRY_SIZE = 32
    const val MAGIC = 0xAA50
    const val MD5_MAGIC = 0xEBEB

    fun parse(image: ByteArray, tableOffsetInImage: Int = 0, flashSize: Long? = null): PartitionTable {
        if (tableOffsetInImage < 0 || tableOffsetInImage + 8 > image.size) {
            throw PartitionTableException("パーティションテーブルを読める位置にありません")
        }
        val entries = ArrayList<PartitionEntry>()
        var cursor = tableOffsetInImage
        var md5Offset = -1
        var tableBytes = 0
        while (cursor + ENTRY_SIZE <= image.size && tableBytes < TABLE_MAX_SIZE) {
            val magic = readUInt16(image, cursor)
            if (magic == MD5_MAGIC) {
                md5Offset = cursor
                tableBytes += ENTRY_SIZE
                break
            }
            if (magic != MAGIC) break
            val type = image[cursor + 2].toInt() and 0xFF
            val subtype = image[cursor + 3].toInt() and 0xFF
            val offset = LittleFsFormat.readUInt32(image, cursor + 4)
            val size = LittleFsFormat.readUInt32(image, cursor + 8)
            val labelBytes = image.copyOfRange(cursor + 12, cursor + 28)
            val label = String(labelBytes, Charsets.UTF_8).trimEnd('\u0000').trim()
            val flags = LittleFsFormat.readUInt32(image, cursor + 28)
            entries += PartitionEntry(
                label = label.ifEmpty { "$type/$subtype" },
                type = type,
                subtype = subtype,
                offset = offset,
                size = size,
                flags = flags,
            )
            cursor += ENTRY_SIZE
            tableBytes += ENTRY_SIZE
        }
        if (entries.isEmpty()) {
            throw PartitionTableException(
                "パーティションテーブルが見つかりません (magic 0xAA50 がありません)",
            )
        }
        val md5Valid = if (md5Offset > 0) {
            val expected = image.copyOfRange(md5Offset + 16, md5Offset + 32)
            val actual = md5(image, tableOffsetInImage, md5Offset)
            expected.contentEquals(actual)
        } else {
            null
        }
        if (flashSize != null) {
            for (entry in entries) {
                if (entry.endOffset > flashSize) {
                    throw PartitionTableException(
                        "${entry.label} がフラッシュサイズを超えています (0x%X > 0x%X)"
                            .format(entry.endOffset, flashSize),
                    )
                }
            }
        }
        return PartitionTable(entries = entries, md5Valid = md5Valid, tableBytes = tableBytes)
    }

    /** Parses a flash image that starts at [PartitionTableParser.TABLE_OFFSET] (a 4 KB table read). */
    fun parseTableImage(tableImage: ByteArray, flashSize: Long? = null): PartitionTable =
        parse(tableImage, 0, flashSize)

    /** True when the bytes look like the start of a partition table (magic present). */
    fun looksLikePartitionTable(bytes: ByteArray, offset: Int = 0): Boolean =
        bytes.size >= offset + 2 && readUInt16(bytes, offset) == MAGIC

    fun md5(image: ByteArray, from: Int, to: Int): ByteArray =
        MessageDigest.getInstance("MD5").digest(image.copyOfRange(from, to))

    private fun readUInt16(buffer: ByteArray, offset: Int): Int =
        (buffer[offset].toInt() and 0xFF) or ((buffer[offset + 1].toInt() and 0xFF) shl 8)
}
