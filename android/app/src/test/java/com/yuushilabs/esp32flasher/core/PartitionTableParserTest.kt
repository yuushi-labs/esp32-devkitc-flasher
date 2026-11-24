package com.yuushilabs.esp32flasher.core

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PartitionTableParserTest {
    private fun entry(
        type: Int,
        subtype: Int,
        offset: Long,
        size: Long,
        label: String,
        withMd5: Boolean = true,
    ): ByteArray {
        val bytes = ByteArray(PartitionTableParser.ENTRY_SIZE)
        bytes[0] = 0x50
        bytes[1] = 0xAA.toByte()
        bytes[2] = type.toByte()
        bytes[3] = subtype.toByte()
        LittleFsFormat.writeUInt32(bytes, 4, offset)
        LittleFsFormat.writeUInt32(bytes, 8, size)
        val name = label.toByteArray(Charsets.UTF_8)
        name.copyInto(bytes, 12)
        return bytes
    }

    private fun table(entries: List<ByteArray>): ByteArray {
        val body = entries.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        val md5 = MessageDigest.getInstance("MD5").digest(body)
        val md5Entry = ByteArray(PartitionTableParser.ENTRY_SIZE)
        md5Entry[0] = 0xEB.toByte()
        md5Entry[1] = 0xEB.toByte()
        md5Entry[2] = 0xFF.toByte()
        md5Entry[3] = 0xFF.toByte()
        md5.copyInto(md5Entry, 16)
        return (body + md5Entry).copyOf(PartitionTableParser.TABLE_MAX_SIZE)
    }

    private val defaultTable = table(
        listOf(
            entry(PartitionEntry.TYPE_DATA, PartitionEntry.SUBTYPE_NVS, 0x9000, 0x4000, "nvs"),
            entry(PartitionEntry.TYPE_DATA, PartitionEntry.SUBTYPE_OTA, 0xE000, 0x2000, "otadata"),
            entry(0x00, 0x00, 0x10000, 0x140000, "app0"),
            entry(0x00, 0x10, 0x150000, 0x140000, "app1"),
            entry(PartitionEntry.TYPE_DATA, PartitionEntry.SUBTYPE_SPIFFS, 0x290000, 0x160000, "spiffs"),
            entry(PartitionEntry.TYPE_DATA, PartitionEntry.SUBTYPE_COREDUMP, 0x3F0000, 0x10000, "coredump"),
        ),
    )

    @Test
    fun `reads the default 4MB table`() {
        val parsed = PartitionTableParser.parseTableImage(defaultTable)

        assertEquals(6, parsed.entries.size)
        assertEquals(true, parsed.md5Valid)
        assertEquals("nvs", parsed.entries.first().label)
        val spiffs = parsed.entries.first { it.label == "spiffs" }
        assertEquals(0x290000L, spiffs.offset)
        assertEquals(0x160000L, spiffs.size)
        assertEquals("spiffs", spiffs.subtypeLabel)
        assertTrue(spiffs.isFilesystem)
        assertFalse(spiffs.isLittleFs)
        assertEquals("0x4000", "0x%X".format(parsed.entries.first().size))
    }

    @Test
    fun `detects a littlefs partition and prefers it over spiffs`() {
        val littlefs = table(
            listOf(
                entry(0x00, 0x00, 0x10000, 0x1E0000, "app0"),
                entry(PartitionEntry.TYPE_DATA, PartitionEntry.SUBTYPE_SPIFFS, 0x3D0000, 0x10000, "spiffs"),
                entry(PartitionEntry.TYPE_DATA, PartitionEntry.SUBTYPE_LITTLEFS, 0x3E0000, 0x20000, "littlefs"),
            ),
        )
        val parsed = PartitionTableParser.parseTableImage(littlefs)

        assertEquals(2, parsed.filesystems.size)
        assertNotNull(parsed.littleFs)
        assertEquals("littlefs", parsed.preferredFilesystem?.label)
        assertEquals(0x20000L, parsed.preferredFilesystem?.size)
        assertTrue(PartitionTableParser.looksLikePartitionTable(littlefs))
    }

    @Test
    fun `follows the table of the default offset inside a flash dump`() {
        val dump = ByteArray(0x10000)
        defaultTable.copyInto(dump, PartitionTableParser.TABLE_OFFSET)
        val parsed = PartitionTableParser.parse(dump, PartitionTableParser.TABLE_OFFSET)

        assertEquals(6, parsed.entries.size)
        assertEquals(true, parsed.md5Valid)
    }

    @Test
    fun `reports a broken MD5 instead of failing`() {
        val broken = defaultTable.copyOf()
        broken[20] = (broken[20].toInt() xor 0xFF).toByte()
        val parsed = PartitionTableParser.parseTableImage(broken)

        assertEquals(false, parsed.md5Valid)
        assertEquals(6, parsed.entries.size)
    }

    @Test
    fun `rejects bytes that are not a partition table`() {
        val garbage = ByteArray(PartitionTableParser.TABLE_MAX_SIZE) { 0x5A }
        assertThrows(PartitionTableException::class.java) {
            PartitionTableParser.parseTableImage(garbage)
        }
        assertThrows(PartitionTableException::class.java) {
            PartitionTableParser.parseTableImage(ByteArray(4))
        }
        assertFalse(PartitionTableParser.looksLikePartitionTable(garbage))
    }

    @Test
    fun `rejects entries that run past the flash size`() {
        val tooBig = table(
            listOf(entry(PartitionEntry.TYPE_DATA, PartitionEntry.SUBTYPE_LITTLEFS, 0x3F0000, 0x20000, "fs")),
        )
        assertThrows(PartitionTableException::class.java) {
            PartitionTableParser.parseTableImage(tooBig, flashSize = 0x400000)
        }
    }

    @Test
    fun `stops at the first entry without the magic`() {
        val table = table(
            listOf(
                entry(PartitionEntry.TYPE_DATA, PartitionEntry.SUBTYPE_NVS, 0x9000, 0x4000, "nvs"),
                entry(PartitionEntry.TYPE_DATA, PartitionEntry.SUBTYPE_LITTLEFS, 0x100000, 0x100000, "littlefs"),
            ),
        )
        // Wipe the third entry (the MD5 entry of this small table stays intact and is acted on).
        table.fill(0xFF.toByte(), 64, 96)
        val parsed = PartitionTableParser.parseTableImage(table)
        assertEquals(2, parsed.entries.size)
    }

    @Test
    fun `subtype labels cover the ESP-IDF names`() {
        val entries = listOf(
            PartitionEntry("a", PartitionEntry.TYPE_DATA, PartitionEntry.SUBTYPE_NVS, 0, 0x1000),
            PartitionEntry("b", PartitionEntry.TYPE_DATA, PartitionEntry.SUBTYPE_OTA, 0, 0x1000),
            PartitionEntry("c", PartitionEntry.TYPE_DATA, PartitionEntry.SUBTYPE_COREDUMP, 0, 0x1000),
            PartitionEntry("d", PartitionEntry.TYPE_DATA, PartitionEntry.SUBTYPE_FAT, 0, 0x1000),
            PartitionEntry("e", PartitionEntry.TYPE_DATA, PartitionEntry.SUBTYPE_SPIFFS, 0, 0x1000),
            PartitionEntry("f", PartitionEntry.TYPE_DATA, PartitionEntry.SUBTYPE_LITTLEFS, 0, 0x1000),
            PartitionEntry("g", 0x00, 0x00, 0, 0x1000),
            PartitionEntry("h", 0x00, 0x12, 0, 0x1000),
        )
        assertEquals(
            listOf("nvs", "ota", "coredump", "fat", "spiffs", "littlefs", "factory", "ota_2"),
            entries.map { it.subtypeLabel },
        )
        assertTrue(entries[5].isLittleFs)
        assertNull(PartitionTable(entries, null, 0).find("zzz"))
    }
}
