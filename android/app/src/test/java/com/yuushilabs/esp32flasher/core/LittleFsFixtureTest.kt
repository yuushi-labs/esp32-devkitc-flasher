package com.yuushilabs.esp32flasher.core

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reads the committed reference images that `littlefs-python` wrote (see
 * `tools/littlefs_interop.py testresources`). They are the "foreign image" side of the interop
 * proof: the app's reader must reproduce them byte for byte without the C library.
 */
class LittleFsFixtureTest {
    private fun resource(name: String): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
            ?: throw AssertionError("テストリソースがありません: $name")
        return stream.use { it.readBytes() }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manifest(): Map<String, Map<String, String>> {
        val text = resource("littlefs/manifest.tsv").toString(Charsets.UTF_8)
        val result = LinkedHashMap<String, MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        for (line in text.lines()) {
            if (line.isBlank()) continue
            if (!line.startsWith("  ")) {
                val name = line.substringBefore('\t')
                current = LinkedHashMap()
                result[name] = current
            } else {
                val path = line.trim().substringBefore('\t')
                val digest = line.trim().substringAfter('\t')
                current?.put(path, digest)
            }
        }
        return result
    }

    @Test
    fun `littlefs-python images are readable and byte identical`() {
        val entries = manifest()
        assertTrue("manifest が空です", entries.isNotEmpty())

        for ((name, files) in entries) {
            val image = resource("littlefs/$name.bin")
            val reader = LittleFsReader(image)
            assertEquals(2L, (reader.superblock.version shr 16) and 0xFFFF)
            assertEquals(4096, reader.superblock.blockSize)
            assertEquals(image.size / 4096, reader.superblock.blockCount)

            val listed = reader.files().associateBy { it.path }
            assertEquals("$name のファイル一覧", files.keys, listed.keys)
            for ((path, expected) in files) {
                val data = reader.read(listed.getValue(path))
                assertEquals("$name / $path のサイズ", listed.getValue(path).size, data.size.toLong())
                assertEquals("$name / $path の内容", expected, sha256(data))
            }
        }
    }

    @Test
    fun `fixture directories are visible`() {
        val mixed = LittleFsReader(resource("littlefs/littlefs-python-mixed.bin")).list()
        assertEquals(setOf("docs", "docs/sub", "フォルダ"), mixed.filter { it.isDirectory }.map { it.path }.toSet())
        assertTrue(mixed.any { it.path == "empty.txt" && !it.isDirectory && it.size == 0L })
        assertTrue(mixed.any { it.path == "docs/memo.txt" && it.size == 400L })
    }

    @Test
    fun `fixture with a CTZ file is read across blocks`() {
        val reader = LittleFsReader(resource("littlefs/littlefs-python-big.bin"))
        val files = reader.files()
        assertEquals(1, files.size)
        assertEquals(20000L, files.first().size)
        val data = reader.read(files.first())
        assertEquals(20000, data.size)
        assertEquals(0x00, data[0].toInt())
        assertEquals((19999 * 7) % 256, data[19999].toInt() and 0xFF)
    }

    @Test
    fun `empty fixture is mountable but empty`() {
        val reader = LittleFsReader(resource("littlefs/littlefs-python-empty.bin"))
        assertTrue(reader.list().isEmpty())
        assertEquals(0L, reader.totalBytes())
    }

    @Test
    fun `images built here are recognised as littlefs`() {
        val image = LittleFsImageBuilder.build(
            listOf(LittleFsFile("a.txt", "a".toByteArray())),
            partitionSize = 32 * 1024,
        )
        assertTrue(LittleFsReader.isLittleFs(image))
        assertTrue(!LittleFsReader.isLittleFs(ByteArray(64)))
    }

    @Test
    fun `helper scripts exist for the python side of the proof`() {
        val script = File("src/../../../tools/littlefs_interop.py").canonicalFile
        assertTrue("tools/littlefs_interop.py が見つかりません: $script", script.isFile)
        val text = script.readText()
        assertTrue(text.contains("littlefs-python"))
    }

    @Test
    fun `fixture image can be modified through a rebuilt copy`() {
        // Reading a foreign image and rebuilding the same tree through our writer must produce an
        // image the app can read back identically (no dependence on littlefs's own layout choices).
        val reader = LittleFsReader(resource("littlefs/littlefs-python-mixed.bin"))
        val files = reader.files().map { LittleFsFile(it.path, reader.read(it)) }
        val rebuilt = LittleFsImageBuilder.build(files, partitionSize = 64 * 1024)
        val reread = LittleFsReader(rebuilt)
        assertEquals(files.map { it.normalizedPath }.toSet(), reread.files().map { it.path }.toSet())
        for (file in files) {
            assertArrayEquals(reader.read(file.normalizedPath), reread.read(file.normalizedPath))
        }
    }
}
