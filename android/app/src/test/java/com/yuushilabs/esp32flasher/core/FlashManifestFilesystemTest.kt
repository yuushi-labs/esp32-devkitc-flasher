package com.yuushilabs.esp32flasher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `filesystems` section of `flash-manifest.json` (littlefs / spiffs images). */
class FlashManifestFilesystemTest {
    private val manifest = """
        {
          "version": "0.2.0",
          "chip": "esp32",
          "merged": { "file": "firmware-merged.bin", "address": "0x0" },
          "filesystems": [
            {
              "type": "littlefs",
              "asset": "littlefs.bin",
              "offset": "0x290000",
              "size": "0x160000",
              "sha256": "aa11bb22cc33dd44ee55ff6677889900aa11bb22cc33dd44ee55ff6677889900"
            },
            { "type": "spiffs", "file": "spiffs.bin", "offset": "0x3D0000" }
          ]
        }
    """.trimIndent()

    @Test
    fun `filesystems are parsed with their offsets`() {
        val parsed = FlashManifestParser.parseManifest(manifest)

        assertEquals(2, parsed.filesystems.size)
        val littlefs = parsed.filesystems.first()
        assertEquals("littlefs", littlefs.type)
        assertEquals("littlefs.bin", littlefs.fileName)
        assertEquals(0x290000L, littlefs.address)
        assertEquals(0x160000L, littlefs.sizeBytes)
        assertEquals("0x290000", littlefs.addressLabel)
        assertTrue(littlefs.isLittleFs)
        assertFalse(littlefs.isSpiffs)

        val spiffs = parsed.filesystems.last()
        assertEquals("spiffs", spiffs.type)
        assertEquals(0x3D0000L, spiffs.address)
        assertTrue(spiffs.isSpiffs)
        assertEquals(1, parsed.allImages.size)
        assertEquals(3, parsed.flashableImages.size)
    }

    @Test
    fun `a filesystem without an offset falls back to the ESP32 default layout`() {
        val parsed = FlashManifestParser.parseManifest(
            """{"filesystems":[{"type":"littlefs","asset":"littlefs.bin"}]}""",
        )
        assertEquals(AddressPreset.LITTLEFS_DEFAULT.address, parsed.filesystems.first().address)
        assertEquals(AddressPreset.LITTLEFS_DEFAULT.address, FlashManifestParser.defaultFilesystemOffset("fat"))
    }

    @Test
    fun `filesystem images can be selected for flashing`() {
        val parsed = FlashManifestParser.parseManifest(manifest)
        val image = parsed.flashableImages.first { it.fileName == "littlefs.bin" }
        assertEquals(0x290000L, image.address)
        assertNotNull(image.sha256)
        assertEquals(2, FlashManifestParser.selectFilesystems(parsed).size)
    }

    @Test
    fun `a manifest with only filesystems is not empty`() {
        val parsed = FlashManifestParser.parseManifest(
            """{"filesystems":[{"type":"littlefs","offset":"0x290000","asset":"littlefs.bin"}]}""",
        )
        assertTrue(parsed.allImages.isEmpty())
        assertFalse(parsed.isEmpty)
        assertTrue(parsed.flashableImages.isNotEmpty())
    }

    @Test
    fun `address presets cover the ESP32 default layout`() {
        assertEquals(0x1000L, AddressPreset.BOOTLOADER.address)
        assertEquals(0x8000L, AddressPreset.PARTITION_TABLE.address)
        assertEquals(0xE000L, AddressPreset.OTA_DATA.address)
        assertEquals(0x290000L, AddressPreset.LITTLEFS_DEFAULT.address)
        assertEquals(0x3D0000L, AddressPreset.LITTLEFS_MIN_SPIFFS.address)
        assertEquals(
            1_441_792L,
            LittleFsPartitionPreset.DEFAULT_4MB.size,
        )
        assertEquals(0x290000L, LittleFsPartitionPreset.DEFAULT_4MB.offset)
        assertEquals(0x20000L, LittleFsPartitionPreset.MIN_SPIFFS.size)
        assertEquals(0x3D0000L, LittleFsPartitionPreset.MIN_SPIFFS.offset)
    }
}
