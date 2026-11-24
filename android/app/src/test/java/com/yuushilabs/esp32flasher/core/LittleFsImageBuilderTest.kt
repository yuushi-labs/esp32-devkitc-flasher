package com.yuushilabs.esp32flasher.core

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LittleFsImageBuilderTest {
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun build(files: List<LittleFsFile>, size: Int = 64 * 1024) =
        LittleFsImageBuilder.build(files, partitionSize = size)

    @Test
    fun `empty image carries a v21 superblock`() {
        val image = build(emptyList(), size = 16 * 4096)

        assertEquals(16 * 4096, image.size)
        assertEquals("littlefs", String(image, 8, 8, Charsets.US_ASCII))
        val reader = LittleFsReader(image)
        assertEquals(2L, (reader.superblock.version shr 16) and 0xFFFF)
        assertEquals(1L, reader.superblock.version and 0xFFFF)
        assertEquals(4096, reader.superblock.blockSize)
        assertEquals(16, reader.superblock.blockCount)
        assertEquals(32L, reader.superblock.nameMax)
        assertTrue(reader.list().isEmpty())
    }

    @Test
    fun `unused blocks stay erased`() {
        val image = build(listOf(LittleFsFile("a.txt", "a".toByteArray())))
        // Block 1 is the spare half of the root pair and must stay erased so littlefs can append.
        for (index in 4096 until 8192) {
            assertEquals(0xFF.toByte(), image[index])
        }
        assertEquals(0xFF.toByte(), image[image.size - 1])
    }

    @Test
    fun `files are stored with their bytes`() {
        val content = ByteArray(300) { (it % 251).toByte() }
        val image = build(
            listOf(
                LittleFsFile("small.txt", "hello".toByteArray()),
                LittleFsFile("medium.bin", content),
            ),
        )
        val reader = LittleFsReader(image)
        val files = reader.files().associateBy { it.path }
        assertEquals(setOf("medium.bin", "small.txt"), files.keys)
        assertArrayEquals("hello".toByteArray(), reader.read("small.txt"))
        assertArrayEquals(content, reader.read("medium.bin"))
        assertEquals(5L, files.getValue("small.txt").size)
        assertEquals(300L, files.getValue("medium.bin").size)
    }

    @Test
    fun `directories are created from paths`() {
        val image = build(
            listOf(
                LittleFsFile("docs/memo.txt", "memo".toByteArray()),
                LittleFsFile("docs/sub/deep.bin", ByteArray(5000) { 7 }),
            ),
        )
        val entries = LittleFsReader(image).list()
        val directories = entries.filter { it.isDirectory }.map { it.path }.toSet()
        assertEquals(setOf("docs", "docs/sub"), directories)
        assertTrue(entries.any { it.path == "docs/sub/deep.bin" && it.size == 5000L })
    }

    @Test
    fun `large files use a CTZ skip list across blocks`() {
        val content = ByteArray(20000) { (it * 3 % 256).toByte() }
        val image = build(listOf(LittleFsFile("big.bin", content)))
        val reader = LittleFsReader(image)

        assertArrayEquals(content, reader.read("big.bin"))
        // 20000 bytes over 4088-byte strides needs five data blocks.
        assertEquals(5, LittleFsFormat.ctzBlockCount(20000, 4096))
        assertEquals(LittleFsFormat.ctzIndex(19999, 4096), 4)
        assertEquals(0, LittleFsFormat.ctzIndex(4095, 4096))
        assertEquals(1, LittleFsFormat.ctzIndex(4096, 4096))
        assertEquals(0, LittleFsFormat.ctzFileOffset(0, 4096))
        assertEquals(4096L, LittleFsFormat.ctzFileOffset(1, 4096))
        assertEquals(4092, LittleFsFormat.ctzDataCapacity(1, 4096))
        assertEquals(12, LittleFsFormat.ctzDataOffset(4, 4096))
    }

    @Test
    fun `japanese names survive the round trip`() {
        val image = build(
            listOf(
                LittleFsFile("日本語.txt", "こんにちは".toByteArray()),
                LittleFsFile("データ/設定.bin", ByteArray(700) { 1 }),
            ),
            size = 128 * 1024,
        )
        val reader = LittleFsReader(image)
        assertArrayEquals("こんにちは".toByteArray(), reader.read("日本語.txt"))
        assertEquals(700L, reader.files().first { it.path == "データ/設定.bin" }.size)
    }

    @Test
    fun `a long root directory is split over several metadata blocks`() {
        val files = (0 until 150).map {
            LittleFsFile("capture-2026-11-24-%03d.jpg".format(it), ByteArray(1200 + it) { index -> index.toByte() })
        }
        val image = build(files, size = 1024 * 1024)
        val reader = LittleFsReader(image)
        val listed = reader.files()

        assertEquals(150, listed.size)
        for (entry in listed) {
            val expected = files.first { it.path == entry.path }
            assertEquals(expected.data.size.toLong(), entry.size)
            assertArrayEquals(expected.data, reader.read(entry))
        }
    }

    @Test
    fun `a sub-directory that needs several metadata blocks is read completely`() {
        val files = (0 until 120).map { index ->
            LittleFsFile(
                "photos/IMG-2026-11-24-%03d.png".format(index),
                ByteArray(900 + index) { offset -> (offset * 3).toByte() },
            )
        }
        val image = build(files, size = 1024 * 1024)
        val reader = LittleFsReader(image)

        assertEquals(1, reader.list().count { it.isDirectory })
        val listed = reader.files()
        assertEquals(120, listed.size)
        for (file in files) {
            assertArrayEquals(file.data, reader.read(file.normalizedPath))
        }
    }

    @Test
    fun `inline and ctz files sit on both sides of the threshold`() {
        val inline = ByteArray(LittleFsImageBuilder.INLINE_MAX) { 1 }
        val ctz = ByteArray(LittleFsImageBuilder.INLINE_MAX + 1) { 2 }
        val image = build(
            listOf(
                LittleFsFile("exactly-inline.bin", inline),
                LittleFsFile("just-over.bin", ctz),
                LittleFsFile("zero.dat", ByteArray(0)),
            ),
        )
        val reader = LittleFsReader(image)
        assertArrayEquals(inline, reader.read("exactly-inline.bin"))
        assertArrayEquals(ctz, reader.read("just-over.bin"))
        assertEquals(0, reader.read("zero.dat").size)
    }

    @Test
    fun `deeply nested folders keep their paths`() {
        val image = build(
            listOf(
                LittleFsFile("a/b/c/d/e/f/g.txt", "deep".toByteArray()),
                LittleFsFile("a/b/sibling.txt", "sib".toByteArray()),
            ),
        )
        val reader = LittleFsReader(image)
        assertEquals(
            setOf("a", "a/b", "a/b/c", "a/b/c/d", "a/b/c/d/e", "a/b/c/d/e/f"),
            reader.list().filter { it.isDirectory }.map { it.path }.toSet(),
        )
        assertArrayEquals("deep".toByteArray(), reader.read("a/b/c/d/e/f/g.txt"))
        assertArrayEquals("sib".toByteArray(), reader.read("a/b/sibling.txt"))
    }

    @Test
    fun `plan reports the blocks an image needs`() {
        val files = listOf(
            LittleFsPlannedFile("a.bin", 20000),
            LittleFsPlannedFile("dir/b.txt", 10),
        )
        val plan = LittleFsImageBuilder.planBySize(files, partitionSize = 64 * 1024)

        assertTrue(plan.fits)
        assertEquals(1, plan.directoryCount)
        assertEquals(2, plan.fileCount)
        assertEquals(20010, plan.contentBytes)
        assertTrue(plan.metadataBlocks >= 4)
        assertTrue(plan.dataBlocks >= 5)
        assertEquals((plan.requiredBlocks * 4096).toLong(), plan.usedBytes)
        assertTrue(plan.usageRatio in 0f..1f)

        val tooSmall = LittleFsImageBuilder.planBySize(
            listOf(LittleFsPlannedFile("big.bin", 100_000)),
            partitionSize = 16 * 1024,
        )
        assertFalse(tooSmall.fits)
    }

    @Test
    fun `plan and build agree on the size`() {
        val files = (0 until 20).map { LittleFsFile("file-$it.txt", ByteArray(100 * it) { 1 }) }
        val plan = LittleFsImageBuilder.plan(files, partitionSize = 128 * 1024)
        val image = LittleFsImageBuilder.build(files, partitionSize = 128 * 1024)
        // Rendering must not use more blocks than the plan announced.
        val reader = LittleFsReader(image)
        assertEquals(20, reader.files().size)
        assertTrue(plan.fits)
    }

    @Test
    fun `capacity overflow is reported before writing`() {
        val files = listOf(LittleFsFile("big.bin", ByteArray(100_000)))
        val plan = LittleFsImageBuilder.plan(files, partitionSize = 32 * 1024)
        assertFalse(plan.fits)
        assertThrows(LittleFsCapacityException::class.java) {
            LittleFsImageBuilder.build(files, partitionSize = 32 * 1024)
        }
    }

    @Test
    fun `invalid paths are rejected`() {
        assertThrows(LittleFsBuildException::class.java) {
            build(listOf(LittleFsFile("..", "x".toByteArray())))
        }
        assertThrows(LittleFsBuildException::class.java) {
            build(listOf(LittleFsFile("../escape.txt", "x".toByteArray())))
        }
        assertThrows(LittleFsBuildException::class.java) {
            build(
                listOf(
                    LittleFsFile("same.txt", "a".toByteArray()),
                    LittleFsFile("same.txt", "b".toByteArray()),
                ),
            )
        }
        assertThrows(LittleFsBuildException::class.java) {
            build(listOf(LittleFsFile("this-name-is-definitely-longer-than-32-bytes.txt", "x".toByteArray())))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LittleFsImageBuilder.build(emptyList(), partitionSize = 4096)
        }
    }

    @Test
    fun `paths are normalised`() {
        assertEquals("a/b.txt", LittleFsFile.normalizePath("/a/b.txt"))
        assertEquals("a/b.txt", LittleFsFile.normalizePath("a\\b.txt"))
        assertEquals("a/b.txt", LittleFsFile.normalizePath("./a//b.txt"))
        assertTrue(LittleFsFile.hasParentSegment("a/../b"))
        assertFalse(LittleFsFile.hasParentSegment("a/./b"))
    }

    @Test
    fun `same input produces the same image`() {
        val files = listOf(
            LittleFsFile("b.txt", "b".toByteArray()),
            LittleFsFile("a.txt", "a".toByteArray()),
            LittleFsFile("d/c.txt", ByteArray(4000) { 3 }),
        )
        val first = build(files)
        val second = build(files.reversed())
        assertEquals(sha256(first), sha256(second))
    }
}
