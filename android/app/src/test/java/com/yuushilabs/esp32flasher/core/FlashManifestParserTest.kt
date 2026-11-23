package com.yuushilabs.esp32flasher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashManifestParserTest {
    private val sha = "a".repeat(64)

    private val fullManifest = """
        {
          "version": "1.2.3",
          "chip": "esp32",
          "flash_size": "4MB",
          "notes": "demo build",
          "merged": {
            "file": "merged.bin",
            "address": "0x0",
            "size": 1048576,
            "sha256": "$sha"
          },
          "parts": [
            { "name": "bootloader", "file": "bootloader.bin", "address": 4096 },
            { "name": "app", "file": "app.bin", "offset": "0x10000", "sha256": "sha256:bb" }
          ],
          "pins": [
            { "gpio": 4, "label": "LED", "direction": "output", "activeLevel": "high", "note": "status LED" },
            { "gpio": "GPIO5", "label": "BUTTON", "direction": "input", "activeLevel": "low" },
            { "gpio": "0x12", "label": "RELAY" },
            { "gpio": 21, "direction": "bidirectional" }
          ],
          "wiringNotes": ["LED -> GPIO4", "BUTTON -> GPIO5"]
        }
    """.trimIndent()

    @Test
    fun `parses a full manifest`() {
        val manifest = FlashManifestParser.parseManifest(fullManifest)
        assertEquals("1.2.3", manifest.version)
        assertEquals("esp32", manifest.chip)
        assertEquals("4MB", manifest.flashSize)
        assertEquals("demo build", manifest.notes)

        val merged = manifest.merged!!
        assertEquals("merged.bin", merged.fileName)
        assertEquals(0L, merged.address)
        assertEquals(1048576L, merged.sizeBytes)
        assertEquals(sha, merged.sha256)

        assertEquals(2, manifest.parts.size)
        assertEquals(0x1000L, manifest.parts[0].address)
        assertEquals(0x10000L, manifest.parts[1].address)
        assertEquals("bb", manifest.parts[1].sha256)

        assertEquals(3, manifest.allImages.size)
        assertFalse(manifest.isEmpty)
    }

    @Test
    fun `parses manifest pins`() {
        val pins = FlashManifestParser.parseManifest(fullManifest).pins
        assertEquals(4, pins.size)

        val led = pins[0]
        assertEquals(4, led.gpio)
        assertEquals("LED", led.label)
        assertEquals(PinDirection.OUTPUT, led.direction)
        assertEquals(PinActiveLevel.HIGH, led.activeLevel)
        assertEquals("status LED", led.note)
        assertEquals("GPIO4", led.gpioLabel)

        val button = pins[1]
        assertEquals(5, button.gpio)
        assertEquals(PinDirection.INPUT, button.direction)
        assertEquals(PinActiveLevel.LOW, button.activeLevel)
        assertNull(button.note)

        val relay = pins[2]
        assertEquals(0x12, relay.gpio)
        assertEquals(PinDirection.UNKNOWN, relay.direction)

        val fallback = pins[3]
        assertEquals(21, fallback.gpio)
        assertEquals("GPIO21", fallback.label)
        assertEquals(PinDirection.BIDIRECTIONAL, fallback.direction)
    }

    @Test
    fun `parses wiring notes array`() {
        val notes = FlashManifestParser.parseManifest(fullManifest).wiringNotes
        assertEquals(listOf("LED -> GPIO4", "BUTTON -> GPIO5"), notes)
        assertTrue(FlashManifestParser.parseManifest(fullManifest).hasWiringGuide)
    }

    @Test
    fun `wiring notes may be a single multiline string`() {
        val manifest = FlashManifestParser.parseManifest(
            """{"merged":"a.bin","wiringNotes":"line 1\nline 2\n"}""",
        )
        assertEquals(listOf("line 1", "line 2"), manifest.wiringNotes)
    }

    @Test
    fun `pins tolerate missing fields`() {
        val pins = FlashManifestParser.parseManifest(
            """{"merged":"a.bin","pins":[{"label":"GND"},{"gpio":12},{"label":"EN","activeLevel":"active_low"}]}""",
        ).pins
        assertEquals(3, pins.size)
        assertNull(pins[0].gpio)
        assertEquals("GND", pins[0].label)
        assertEquals(12, pins[1].gpio)
        assertEquals("GPIO12", pins[1].label)
        assertEquals(PinActiveLevel.LOW, pins[2].activeLevel)
    }

    @Test
    fun `manifest without pins has no wiring guide`() {
        val manifest = FlashManifestParser.parseManifest("""{"merged":"a.bin"}""")
        assertTrue(manifest.pins.isEmpty())
        assertTrue(manifest.wiringNotes.isEmpty())
        assertFalse(manifest.hasWiringGuide)
    }

    @Test
    fun `merged may be a plain file name`() {
        val manifest = FlashManifestParser.parseManifest(
            """{"merged":"firmware.bin","address":"0x1000","sha256":"cc"}""",
        )
        assertEquals("firmware.bin", manifest.merged?.fileName)
        assertEquals(0x1000L, manifest.merged?.address)
        assertEquals("cc", manifest.merged?.sha256)
    }

    @Test
    fun `accepts files alias with decimal offsets`() {
        val manifest = FlashManifestParser.parseManifest(
            """{"files":[{"filename":"app.bin","offset":65536},{"path":"bootloader.bin"}]}""",
        )
        assertEquals(2, manifest.parts.size)
        assertEquals(65536L, manifest.parts[0].address)
        assertEquals("app.bin", manifest.parts[0].fileName)
        assertEquals("bootloader.bin", manifest.parts[1].fileName)
        assertEquals(0x1000L, manifest.parts[1].address)
    }

    @Test
    fun `rejects invalid manifests`() {
        assertThrows(ManifestFormatException::class.java) {
            FlashManifestParser.parseManifest("not json")
        }
        assertThrows(ManifestFormatException::class.java) {
            FlashManifestParser.parseManifest("[]")
        }
        assertThrows(ManifestFormatException::class.java) {
            FlashManifestParser.parseManifest("{}")
        }
    }

    @Test
    fun `selects merged images by default`() {
        val manifest = FlashManifestParser.parseManifest(fullManifest)
        assertEquals(listOf("merged.bin"), FlashManifestParser.selectImages(manifest).map { it.fileName })
        assertEquals(
            listOf("bootloader.bin", "app.bin"),
            FlashManifestParser.selectImages(manifest, preferMerged = false).map { it.fileName },
        )
    }

    @Test
    fun `select images falls back to parts without a merged image`() {
        val manifest = FlashManifestParser.parseManifest(
            """{"parts":[{"file":"app.bin","address":"0x10000"}]}""",
        )
        assertEquals(listOf("app.bin"), FlashManifestParser.selectImages(manifest).map { it.fileName })
    }

    @Test
    fun `parses a release list with assets`() {
        val releases = FlashManifestParser.parseReleases(
            """
            [
              {
                "tag_name": "v0.2.0",
                "name": "0.2.0 release",
                "published_at": "2026-01-02T03:04:05Z",
                "html_url": "https://github.test/releases/v0.2.0",
                "prerelease": false,
                "draft": false,
                "assets": [
                  { "name": "flash-manifest.json", "browser_download_url": "https://x/manifest.json", "size": 123, "digest": "sha256:$sha" },
                  { "name": "esp32-merged.bin", "browser_download_url": "https://x/merged.bin", "size": 1000 },
                  { "name": "notes.txt", "browser_download_url": "https://x/notes.txt", "size": 10 }
                ]
              },
              { "tag_name": "v0.1.0", "draft": true, "assets": [] }
            ]
            """.trimIndent(),
        )
        assertEquals(2, releases.size)
        val first = releases[0]
        assertEquals("v0.2.0", first.tagName)
        assertEquals("0.2.0", first.versionName)
        assertEquals("0.2.0 release", first.displayName)
        assertEquals("2026-01-02T03:04:05Z", first.publishedAt)
        assertEquals("https://github.test/releases/v0.2.0", first.htmlUrl)
        assertEquals(3, first.assets.size)
        assertEquals("flash-manifest.json", first.manifestAsset?.name)
        assertEquals(sha, first.manifestAsset?.sha256)
        assertEquals(1, first.binaryAssets.size)
        assertEquals("esp32-merged.bin", first.primaryBinary?.name)
        assertTrue(first.hasFlashableContent)

        val second = releases[1]
        assertTrue(second.draft)
        assertFalse(second.hasFlashableContent)
    }

    @Test
    fun `parses a single release object`() {
        val release = FlashManifestParser.parseRelease(
            """{"tag_name":"v1.0.0","assets":[{"name":"app.bin","browser_download_url":"https://x/app.bin"}]}""",
        )
        assertEquals("v1.0.0", release.tagName)
        assertEquals("app.bin", release.primaryBinary?.name)
        assertNull(release.body)
    }

    @Test
    fun `release asset digest is only trusted when it is a sha256`() {
        val asset = ReleaseAsset("a.bin", "https://x", digest = "sha256:$sha")
        assertEquals(sha, asset.sha256)
        assertNull(ReleaseAsset("a.bin", "https://x", digest = "sha256:abcd").sha256)
        assertNull(ReleaseAsset("a.bin", "https://x", digest = null).sha256)
    }

    @Test
    fun `primary binary prefers merged builds`() {
        val release = FirmwareRelease(
            tagName = "v1",
            name = "v1",
            assets = listOf(
                ReleaseAsset("app.bin", "https://x/app.bin"),
                ReleaseAsset("esp32-full.bin", "https://x/full.bin"),
            ),
        )
        assertEquals("esp32-full.bin", release.primaryBinary?.name)
    }

    @Test
    fun `infers flash offsets from asset names`() {
        assertEquals(0x0L, FlashManifestParser.inferAddress("esp32-merged.bin"))
        assertEquals(0x0L, FlashManifestParser.inferAddress("firmware-full.bin"))
        assertEquals(0x1000L, FlashManifestParser.inferAddress("bootloader.bin"))
        assertEquals(0x8000L, FlashManifestParser.inferAddress("partitions.bin"))
        assertEquals(0xE000L, FlashManifestParser.inferAddress("boot_app0.bin"))
        assertEquals(0x10000L, FlashManifestParser.inferAddress("app.bin"))
        assertEquals(0x10000L, FlashManifestParser.inferAddress("firmware.bin"))
        assertEquals(0x0L, FlashManifestParser.inferAddress("mystery.bin"))
    }

    @Test
    fun `sha256 helpers match a known vector`() {
        val abc = "abc".toByteArray()
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            FlashManifestParser.sha256Hex(abc),
        )
        assertTrue(FlashManifestParser.verifySha256(abc, FlashManifestParser.sha256Hex(abc)))
        assertTrue(FlashManifestParser.verifySha256(abc, "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"))
        assertFalse(FlashManifestParser.verifySha256(abc, sha))
        assertTrue(FlashManifestParser.verifySha256(abc, null))
        assertTrue(FlashManifestParser.verifySha256(abc, "too-short"))
    }

    @Test
    fun `manifest images expose a hex address label`() {
        val manifest = FlashManifestParser.parseManifest(fullManifest)
        assertEquals("0x10000", manifest.parts[1].addressLabel)
    }
}
